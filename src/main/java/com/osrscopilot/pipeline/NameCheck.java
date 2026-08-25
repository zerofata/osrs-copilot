package com.osrscopilot.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds proper nouns in an answer that the supplied context does not
 * contain -- the channel through which other games' content leaks in
 * (RS3's "Anachronia" offered as an OSRS location).
 */
class NameCheck
{
	/** Markdown decoration to drop before scanning for capitalisation. */
	private static final Pattern CODE_SPAN = Pattern.compile("`[^`]*`");
	private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
	private static final Pattern MD_MARKS = Pattern.compile("[*_#>\\\\]");
	/** Table cell walls end a phrase the way a full stop does. */
	private static final Pattern CELL_WALL = Pattern.compile("\\|");

	/** A capitalised token: "Varrock", "Zamorak's", "TzTok-Jad". */
	private static final Pattern CAP_TOKEN = Pattern.compile("[A-Z][A-Za-z'\\-]*");

	/** Lowercase words that continue a name ("Tower of Voices").
	 * Prepositions of place are excluded: "Blast Furnace in Keldagrim" is
	 * two names. */
	private static final Set<String> CONNECTORS = Set.of(
		"of", "the", "and", "de", "der", "van");

	/** Pronouns and articles that start sentences. */
	private static final Set<String> ALWAYS_IGNORE = Set.of(
		"i", "you", "your", "it", "its", "a", "an", "the", "this", "that", "these",
		"those", "they", "we", "he", "she", "there", "here");

	private NameCheck()
	{
	}

	/** Proper nouns with no variant present in the context
	 * (case-insensitive containment). */
	static List<String> ungroundedNames(String answer, String context, Set<String> englishWords)
	{
		String haystack = context == null ? "" : context.toLowerCase(Locale.ROOT);
		List<String> out = new ArrayList<>(new LinkedHashSet<>(names(answer, englishWords)));
		out.removeIf(name -> variants(name, englishWords).stream()
			.anyMatch(v -> haystack.contains(v.toLowerCase(Locale.ROOT))));
		return out;
	}

	/** Forms of a name worth trying, most specific first: trimmed sentence
	 * openers ("Requires The Giant Dwarf") and singulars ("Skeletal
	 * Wyverns" often has no page of its own). */
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

		// Phrases are built maximally ("Mining Guild" keeps its head);
		// any separator ends the phrase ("Edgeville, Falador" is two).
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

	/** Whether a name is game vocabulary rather than title-case English
	 * ("Gear Setup" makes no claim; "Anachronia" does). A fabricated name
	 * built purely from dictionary words is missed. */
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

	/** Whether a name and the page it resolved to are the same thing:
	 * plural/casing/qualifier differences are ("Aviansies" -> "Aviansie");
	 * an unrelated title is not ("Anachronia" -> "Fossil Island"). */
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
		// Possessives keep their apostrophe, as page titles do
		// ("Gertrude's Cat"); only the check strips it.
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
