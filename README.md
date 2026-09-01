# OSRS Copilot

A plugin that uses RuneLite integration to give an LLM access to your game state and answers questions, using the OSRS wiki for RAG.

## Requirements

This plugin requires any users to bring their own LLM. OpenRouter is the recommended provider, although anything compatible with the standardized OpenAI spec should work such as vLLM, Llama.cpp or other generic inference providers.

Primarily tested with GLM5.2, GLM5.3, Deepseek v4 Flash 0713, GLM5.3-Flash and locally Muse Glimmer 30B. Smaller models like Muse Glimmer and Qwen 3.8 27B will handle simple queries without issue, but may make mistakes on more complicated queries (although bigger / more expensive models still occasionally have hiccups).

This list was updated at 1, September, 2026 so probably better models will be available and the above list will be outdated if you're looking at this more than a few months in the future.

## Usage

First, download the OSRS Copilot from the Runelite Plugin Hub and accept the warning.

You should then see the OSRS Copilot icon appear in the navigation sidebar, like so.

<img width="272" height="612" alt="image" src="https://github.com/user-attachments/assets/6f9b72bd-465e-466f-8708-7af880374d5f" />

Select LLM settings to open the LLM configuration menu.

Custom = OpenAI compatible spec, anything that generically supports chat completions. Ironically, not OpenAI themselves with most of their recent models.
OpenRouter = Any model available from OpenRouter, they're a centralized inference provider so any model they have available should work.

Temperature = How creative the model is. Leave this at 0.4 unless you know what you're doing.
Max Response Tokens = Max tokens the model will ever output in one turn. This prevents repetition loops, recommended values are 4096 or 8192.
Tool call turns = Number turns the model can spend tool calling, it will be forced to output an answer once these have been reached. Recommended is 3-4, but some models may need more.

Test = Tests your connection, if this passes the plugin is ready to go.

There is also a "Simple" mode, located below the Ask button. This provides the model an instruction to simplify responses and avoid markdown, it also strips forcefully any bullet points type markdown. Useful for using the plugin in the sidebar where space is sparse.

Within the plugin settings if you click the Runelite wrench icon and search for "OSRS Copilot" you can also change the theme and text size.

## How it works

Each query to the model gets routed through a pipeline to assemble the prompt for the model and determine what facts it needs to see.

1. **Capture:** Assembles skills, quest states, inventory/equipment/bank, location,
   slayer task, POH, recent gameplay events.
2. **Route:** Using the English language, deterministically attempt to identify the type of query. Is it transport, combat, item, skilling related or something else. Common queries get special routes which pull in additional information from the wiki beforehand or hide tools / modify instructions to improve the models response.
3. **Prefetch:** Grab wiki pages, monster combat profiles, equipment stats, drop
   tables, and GE prices for everything the question mentions are fetched up
   front and handed to the model as facts.
4. **Response:** The only step actually involving the LLM. Using the provided resources it will then respond to the query, using tools to lookup any additional information if needed.

Answers then get rendered by a decoration mechanism, so if the model generically says Mithril ore is needed, the app will then say if you have the item and where it's stored if so. Same for quest status etc.

## Privacy

Your game state (skills, quests, inventory, equipment, bank) is sent to
the LLM endpoint **you** configure; nothing is sent to any LLM until you set
one. Wiki, Grand Exchange, and hiscores lookups go to
`runescape.wiki`/`runelite.net` APIs over HTTPS.

Additionally, bank contents and POH facilities are stored in JSON files within your runelite directory, required to allow the app to know what you have when not actively looking at your bank and across sessions.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
