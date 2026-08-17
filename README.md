# OSRS Copilot

A RuneLite plugin that answers your questions with your live game state. Ask
"what gear should I bring for my slayer task" or "can I make an adamantite
platebody" in a side panel, and it answers using your actual skills, quest
progress, inventory, equipment, and bank -- grounded in facts fetched from the
OSRS Wiki and Grand Exchange at question time, not the model's memory.

You bring your own LLM: point the plugin at any OpenAI-compatible chat
completions endpoint (a hosted provider or your own local server).

## Usage Examples / Screenshots
<img width="1438" height="760" alt="image" src="https://github.com/user-attachments/assets/8b3b853b-9ebf-4d4c-8d7c-e5796e4a7bd8" />

<img width="271" height="758" alt="image" src="https://github.com/user-attachments/assets/db606af1-0d39-4b99-a0c0-8b4b507fad45" />

## How it works

Each question runs through a deterministic pipeline before the model sees it:

1. **Capture** -- skills, quest states, inventory/equipment/bank, location,
   slayer task, recent gameplay events.
2. **Route** -- entities in the question (items, monsters, quests, slang like
   "kbd" or "addy bars") are resolved against local vocabularies and wiki
   redirects. No LLM involved.
3. **Prefetch** -- wiki pages, monster combat profiles, equipment stats, drop
   tables, and GE prices for everything the question mentions are fetched up
   front and handed to the model as facts.
4. **Synthesize** -- the model answers from the facts and your state, with
   tools available for anything it still needs to look up.

Answers render with entity-aware styling: quests colored by your progress,
items colored by whether you own them, everything linked to the wiki with
real game icons. A disclosure line under each answer shows what was retrieved
and what it cost.

## Running it

**1. Install JDK 11** -- [Adoptium Temurin 11](https://adoptium.net/temurin/releases/?version=11)
(pick the JDK `.msi` for Windows). In the installer, enable the
**"Set JAVA_HOME variable"** feature (it's off by default) -- Gradle needs it.
If Java is installed but you still get a `JAVA_HOME is not set` error, either
re-run the installer and enable that feature, or set it manually to your JDK
folder (e.g. `C:\Program Files\Eclipse Adoptium\jdk-11.x.x-hotspot`).

**2. Clone and run:**

```
git clone https://github.com/zerofata/osrs-copilot
cd osrs-copilot
gradlew run
```

(`gradlew run` in Command Prompt, `.\gradlew run` in PowerShell,
`./gradlew run` on macOS/Linux.)

The first run downloads dependencies and takes a few minutes; later runs are
fast. It launches RuneLite in developer mode with the plugin loaded. Log in
normally (see [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)
if you use a Jagex account), then open the plugin settings (wrench icon ->
OSRS Copilot) and set:

- **Enable copilot** -- off by default; nothing is sent anywhere until you
  turn this on and accept the third-party-server warning
- **API base URL** -- an OpenAI-compatible endpoint, e.g. `https://api.example.com/v1`
- **API key** -- if your endpoint needs one
- **Model** -- the model name your endpoint serves

Settings persist in your RuneLite profile. Click the copilot icon in the
sidebar and ask away. Open your bank once per session so it can be captured --
ownership answers are conditional until then.

Alternatively, `./gradlew shadowJar` builds a single self-contained jar
(client + plugin) you can run anywhere with `java -jar`.

## Privacy

Your game state is sent only to the LLM endpoint **you** configure -- there is
no third-party service of ours. Wiki, Grand Exchange, and hiscores lookups go
to official `runescape.wiki`/`runelite.net` APIs over HTTPS.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
