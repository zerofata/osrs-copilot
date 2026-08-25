package com.osrscopilot.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds proper nouns in an answer that the supplied context does not contain.
 *
 * Names taken from the retrieved facts are grounded by construction; only the
 * rest can be memory, and memory is where content from other games leaks in
 * (RS3's "Anachronia" offered as an OSRS location). Extraction here is
 * deliberately generous and dumb; the narrowing happens against the context
 * and then against the wiki.
 */
class NameCheck
{
	/** Markdown decoration to drop before scanning for capitalisation. */
	private static final Pattern CODE_SPAN = Pattern.compile("`[^`]*`");
	private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
	private static final Pattern MD_MARKS = Pattern.compile("[*_#>\\\\]");
	/** Table cell walls separate names: "| Blast Furnace | Keldagrim |" is
	 * two, so they end a phrase the way a full stop does. */
	private static final Pattern CELL_WALL = Pattern.compile("\\|");

	/** A capitalised token: "Varrock", "Zamorak's", "TzTok-Jad". */
	private static final Pattern CAP_TOKEN = Pattern.compile("[A-Z][A-Za-z'\\-]*");

	/**
	 * Lowercase words that continue a name rather than ending it ("Tower of
	 * Voices"). Prepositions of place are excluded: "Blast Furnace in
	 * Keldagrim" is two names, not one.
	 */
	private static final Set<String> CONNECTORS = Set.of(
		"of", "the", "and", "de", "der", "van");

	/**
	 * Pronouns and articles that start sentences; the common-English wordlist
	 * covers the rest of the generic vocabulary, so this stays tiny.
	 */
	private static final Set<String> ALWAYS_IGNORE = Set.of(
		"i", "you", "your", "it", "its", "a", "an", "the", "this", "that", "these",
		"those", "they", "we", "he", "she", "there", "here");

	private NameCheck()
	{
	}

	/**
	 * Proper nouns in the answer that no variant of appears in the context.
	 * Case-insensitive containment, so "blast furnace" in a wiki table grounds
	 * "Blast Furnace" in prose.
	 */
	static List<String> ungroundedNames(String answer, String context, Set<String> englishWords)
	{
		String haystack = context == null ? "" : context.toLowerCase(Locale.ROOT);
		List<String> out = new ArrayList<>(new LinkedHashSet<>(names(answer, englishWords)));
		out.removeIf(name -> variants(name, englishWords).stream()
			.anyMatch(v -> haystack.contains(v.toLowerCase(Locale.ROOT))));
		return out;
	}

	/**
	 * Forms of a name worth trying, most specific first. A sentence opener
	 * glues ordinary English to a real name ("Requires The Giant Dwarf"), and
	 * plurals often have no page of their own ("Skeletal Wyverns"), so both
	 * the trimmed and singular forms count as the same claim.
	 */
	static List<String> variants(String name, Set<String> englishWords)
	{
		Set<String> out = new LinkedHashSet<>();
		String candidate = name;
		while (true)
		{
			out.add(candidate);
			if (candidate.length() > 3 && candidate.endsWith("s"))
			{
				out.add(candidate.substring(0, candidate.length() - 1));
			}
			int space = candidate.indexOf(' ');
			if (space < 0 || !isGeneric(candidate.substring(0, space), englishWords))
			{
				break;
			}
			candidate = candidate.substring(space + 1);
		}
		return new ArrayList<>(out);
	}

	/** Every proper-noun phrase in the text, before any grounding check. */
	static List<String> names(String text, Set<String> englishWords)
	{
		String clean = CELL_WALL.matcher(MD_MARKS.matcher(
			MD_LINK.matcher(CODE_SPAN.matcher(text).replaceAll(" "))
				.replaceAll("$1")).replaceAll(" ")).replaceAll("\n");
		// Sentence boundaries end a name: a following capital starts a new one.
		List<String> found = new ArrayList<>();
		for (String sentence : clean.split("(?<=[.!?:;\\n])"))
		{
			collectFromSentence(sentence, found, englishWords);
		}
		return found;
	}

	private static void collectFromSentence(String sentence, List<String> found,
		Set<String> englishWords)
	{
		String[] words = sentence.trim().split("\\s+");
		int i = 0;
		while (i < words.length)
		{
			String word = strip(words[i]);
			if (!isCapitalised(word))
			{
				i++;
				continue;
			}

			// Phrases are built maximally: "Mining Guild" must not lose its
			// head just because "mining" is a generic word on its own. Any
			// separator ends the phrase, so a list stays a list ("Edgeville,
			// Falador" is two names).
			StringBuilder phrase = new StringBuilder(word);
			int j = i + 1;
			while (j < words.length && !endsPhrase(words[j - 1]))
			{
				String next = strip(words[j]);
				if (isCapitalised(next))
				{
					phrase.append(' ').append(next);
					j++;
				}
				else if (CONNECTORS.contains(next.toLowerCase(Locale.ROOT))
					&& !endsPhrase(words[j]) && j + 1 < words.length
					&& isCapitalised(strip(words[j + 1])))
				{
					phrase.append(' ').append(next).append(' ').append(strip(words[j + 1]));
					j += 2;
				}
				else
				{
					break;
				}
			}

			// A lone capitalised English word asserts nothing worth checking.
			String candidate = phrase.toString();
			if (candidate.indexOf(' ') >= 0 || !isGeneric(candidate, englishWords))
			{
				found.add(candidate);
			}
			i = j;
		}
	}

	/**
	 * Whether a name is game-specific vocabulary rather than ordinary
	 * title-case English. Answers are full of capitalised prose -- section
	 * labels ("Gear Setup"), emphasis, table headers -- and none of it makes a
	 * claim about the game world, while the leaks worth catching are words
	 * English doesn't have ("Anachronia", "Menaphos"). Costs the ability to
	 * catch a fabricated name built only from dictionary words.
	 */
	static boolean looksGameSpecific(String name, Set<String> englishWords)
	{
		for (String word : name.split("[\\s\\-]+"))
		{
			if (word.isEmpty() || CONNECTORS.contains(word.toLowerCase(Locale.ROOT)))
			{
				continue;
			}
			if (isGeneric(word, englishWords))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether a name and the wiki page it resolved to refer to the same thing.
	 * Plural, casing, punctuation and qualifier differences are the same thing
	 * ("Aviansies" -> "Aviansie", "Wilderness Resource Area" -> "Resource
	 * Area"); an unrelated title is not ("Anachronia" -> "Fossil Island").
	 */
	static boolean namesSameThing(String name, String resolvedTitle)
	{
		String a = fold(name);
		String b = fold(resolvedTitle);
		return a.contains(b) || b.contains(a) || a.equals(initials(resolvedTitle));
	}

	/** Initials of a title's significant words, so player abbreviations count
	 * as the thing they abbreviate ("KBD" -> "King Black Dragon"). */
	private static String initials(String title)
	{
		StringBuilder sb = new StringBuilder();
		for (String word : title.split("[\\s\\-]+"))
		{
			if (!word.isEmpty() && !CONNECTORS.contains(word.toLowerCase(Locale.ROOT)))
			{
				sb.append(Character.toLowerCase(word.charAt(0)));
			}
		}
		return fold(sb.toString());
	}

	/** A word ordinary English uses, so capitalisation alone doesn't make it
	 * a name. */
	private static boolean isGeneric(String word, Set<String> englishWords)
	{
		if (isGenericExact(fold(word), englishWords))
		{
			return true;
		}
		// Contractions are ordinary English wearing a capital ("You'll").
		// Possessives keep their apostrophe in phrases, because page titles
		// do too ("Gertrude's Cat"), so only the check strips it.
		int tick = word.indexOf('\'');
		return tick > 0 && isGenericExact(fold(word.substring(0, tick)), englishWords);
	}

	private static boolean isGenericExact(String folded, Set<String> englishWords)
	{
		return ALWAYS_IGNORE.contains(folded)
			|| (englishWords != null && englishWords.contains(folded));
	}

	/** Lowercase, drop non-alphanumerics and any plural ending. */
	private static String fold(String s)
	{
		String folded = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		return folded.endsWith("s") ? folded.substring(0, folded.length() - 1) : folded;
	}

	/** Whether the raw token carries trailing punctuation that separates it
	 * from whatever follows. */
	private static boolean endsPhrase(String rawWord)
	{
		for (int i = rawWord.length() - 1; i >= 0; i--)
		{
			char c = rawWord.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '\'')
			{
				return false;
			}
			if (",;:)]}\"/&".indexOf(c) >= 0)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isCapitalised(String word)
	{
		if (word.length() < 2)
		{
			return false;
		}
		Matcher m = CAP_TOKEN.matcher(word);
		return m.matches();
	}

	/** Trims surrounding punctuation while keeping internal apostrophes. */
	private static String strip(String word)
	{
		int start = 0;
		int end = word.length();
		while (start < end && !Character.isLetterOrDigit(word.charAt(start)))
		{
			start++;
		}
		while (end > start && !Character.isLetterOrDigit(word.charAt(end - 1))
			&& word.charAt(end - 1) != '\'')
		{
			end--;
		}
		return word.substring(start, end);
	}
}
