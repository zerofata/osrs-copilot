package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import okhttp3.OkHttpClient;

/**
 * Measures the grounding check's noise level against real answers.
 *
 * Usage: --answer FILE [--context FILE]
 */
public class NameCheckScan
{
	public static void main(String[] args) throws Exception
	{
		PrintStream out = new PrintStream(System.out, true, "UTF-8");
		String answerFile = null;
		String contextFile = null;
		for (int i = 0; i < args.length - 1; i++)
		{
			if ("--answer".equals(args[i]))
			{
				answerFile = args[i + 1];
			}
			if ("--context".equals(args[i]))
			{
				contextFile = args[i + 1];
			}
		}

		String answer = read(answerFile);
		String context = contextFile == null ? "" : read(contextFile);

		File cacheDir = new File(new File(System.getProperty("user.home"), ".runelite"),
			"osrs-copilot/cache");
		Gson gson = new Gson();
		WikiApi wiki = new WikiApi(new Http(new OkHttpClient(), gson), gson, cacheDir);

		List<String> all = NameCheck.names(answer, wiki.englishWords());
		List<GroundingCheck.Finding> findings = GroundingCheck.check(answer, context, wiki);
		out.printf("names in answer: %d   ungrounded: %d%n", all.size(), findings.size());

		for (GroundingCheck.Verdict v : GroundingCheck.Verdict.values())
		{
			out.println("\n--- " + v + " ---");
			findings.stream().filter(f -> f.verdict == v).forEach(f -> out.println("  " + f));
		}
		out.printf("%nsuspect %d of %d ungrounded%n",
			GroundingCheck.suspect(findings).size(), findings.size());
		System.exit(0);
	}

	private static String read(String path) throws Exception
	{
		return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
	}
}
