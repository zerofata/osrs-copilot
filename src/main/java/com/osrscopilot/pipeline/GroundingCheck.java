package com.osrscopilot.pipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks an answer's proper nouns against the OSRS wiki, after discarding the
 * ones the supplied context already grounds.
 *
 * The wiki is the closed vocabulary of what exists in this game, and the page
 * a name lands on says how the name relates to it: an unchanged or
 * near-identical title is the thing itself, a wholly different title means the
 * name is not this game's name for anything, and no page at all means the wiki
 * has never heard of it. One batched request per answer, no LLM.
 */
class GroundingCheck
{
	enum Verdict
	{
		/** Real OSRS content the model recalled without being told. */
		OK,
		/** Resolves to a differently-named page: another game's name, or a rename. */
		OTHER_NAME,
		/** No page under any form of the name. */
		UNKNOWN
	}

	static class Finding
	{
		final String name;
		final String resolvedTitle;
		final Verdict verdict;

		Finding(String name, String resolvedTitle, Verdict verdict)
		{
			this.name = name;
			this.resolvedTitle = resolvedTitle;
			this.verdict = verdict;
		}

		@Override
		public String toString()
		{
			return verdict == Verdict.OTHER_NAME ? name + " -> " + resolvedTitle : name;
		}
	}

	private GroundingCheck()
	{
	}

	/** One finding per ungrounded proper noun in the answer. */
	static List<Finding> check(String answer, String context, WikiApi wiki) throws IOException
	{
		Set<String> english = wiki.englishWords();
		List<String> ungrounded = NameCheck.ungroundedNames(answer, context, english);
		ungrounded.removeIf(name -> !NameCheck.looksGameSpecific(name, english));
		if (ungrounded.isEmpty())
		{
			return List.of();
		}

		// Every form of every name resolves in a single batched request.
		Set<String> allVariants = new LinkedHashSet<>();
		for (String name : ungrounded)
		{
			allVariants.addAll(NameCheck.variants(name, english));
		}
		Map<String, String> resolved = wiki.resolveTitles(allVariants);

		List<Finding> findings = new ArrayList<>();
		for (String name : ungrounded)
		{
			findings.add(classify(name, english, resolved));
		}
		return findings;
	}

	/** Names flagged as not this game's -- the ones worth acting on. */
	static List<Finding> suspect(List<Finding> findings)
	{
		List<Finding> out = new ArrayList<>();
		for (Finding f : findings)
		{
			if (f.verdict != Verdict.OK)
			{
				out.add(f);
			}
		}
		return out;
	}

	private static Finding classify(String name, Set<String> english,
		Map<String, String> resolved)
	{
		String firstResolvedTitle = null;
		for (String variant : NameCheck.variants(name, english))
		{
			String title = resolved.get(variant);
			if (title == null)
			{
				continue;
			}
			// Any form that names its own page settles the matter.
			if (NameCheck.namesSameThing(variant, title))
			{
				return new Finding(name, title, Verdict.OK);
			}
			if (firstResolvedTitle == null)
			{
				firstResolvedTitle = title;
			}
		}
		return firstResolvedTitle == null
			? new Finding(name, null, Verdict.UNKNOWN)
			: new Finding(name, firstResolvedTitle, Verdict.OTHER_NAME);
	}
}
