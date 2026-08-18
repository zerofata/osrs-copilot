"""Builds the synthetic GameCapture fixtures the eval batteries run against:

  snapshot-sample.json          NORMAL account, 30-item bank (inlines: <= 200)
  snapshot-sample-ironman.json  same account as an IRONMAN
  snapshot-sample-bigbank.json  bank padded past Router.BANK_INLINE_LIMIT so
                                routing flips to bank=summarized

All fixtures are safe to publish. Quest NAMES are copied from a real capture
(the resolver matches quest entities against them -- they are game data, not
player data); every player-specific field (name, levels, bank, states) is
invented.

Usage: python eval/make-sample-snapshot.py PATH_TO_REAL_CAPTURE
"""
import json
import sys

src_path = sys.argv[1]
raw = open(src_path, 'rb').read()
enc = 'utf-16' if raw[:2] in (b'\xff\xfe', b'\xfe\xff') else 'utf-8-sig'
real = json.loads(raw.decode(enc))

# A deterministic mid-game account: most quests done, a fixed set not.
unfinished = {
    "Song of the Elves", "Desert Treasure II - The Fallen Empire",
    "Dragon Slayer II", "Monkey Madness II", "The Blood Moon Rises",
    "Sins of the Father", "Secrets of the North", "While Guthix Sleeps",
    "The Frozen Door", "Barbarian Training",
}
quest_states = {}
for name in sorted(real["questStates"]):
    quest_states[name] = "NOT_STARTED" if name in unfinished else "FINISHED"

skills = {
    "Attack": 80, "Defence": 78, "Strength": 82, "Hitpoints": 81,
    "Ranged": 80, "Prayer": 70, "Magic": 79, "Cooking": 75,
    "Woodcutting": 70, "Fletching": 65, "Fishing": 68, "Firemaking": 70,
    "Crafting": 66, "Smithing": 65, "Mining": 68, "Herblore": 66,
    "Agility": 65, "Thieving": 62, "Slayer": 75, "Farming": 65,
    "Runecraft": 55, "Hunter": 60, "Construction": 60, "Sailing": 20,
}
# Rough level->xp curve is irrelevant to routing; round numbers keep it
# obviously synthetic.
skill_xp = {k: v * 25000 for k, v in skills.items()}

bank = [
    {"id": 4151, "name": "Abyssal whip", "quantity": 1},
    {"id": 11840, "name": "Dragon boots", "quantity": 1},
    {"id": 6585, "name": "Amulet of fury", "quantity": 1},
    {"id": 1704, "name": "Amulet of glory", "quantity": 4},
    {"id": 4587, "name": "Dragon scimitar", "quantity": 1},
    {"id": 1187, "name": "Dragon sq shield", "quantity": 1},
    {"id": 1163, "name": "Rune full helm", "quantity": 1},
    {"id": 1127, "name": "Rune platebody", "quantity": 1},
    {"id": 1079, "name": "Rune platelegs", "quantity": 1},
    {"id": 2434, "name": "Prayer potion(4)", "quantity": 40},
    {"id": 6685, "name": "Saradomin brew(4)", "quantity": 20},
    {"id": 3024, "name": "Super restore(4)", "quantity": 30},
    {"id": 12695, "name": "Super combat potion(4)", "quantity": 15},
    {"id": 385, "name": "Shark", "quantity": 300},
    {"id": 554, "name": "Fire rune", "quantity": 5000},
    {"id": 556, "name": "Air rune", "quantity": 5000},
    {"id": 560, "name": "Death rune", "quantity": 1200},
    {"id": 565, "name": "Blood rune", "quantity": 800},
    {"id": 9075, "name": "Astral rune", "quantity": 600},
    {"id": 861, "name": "Magic shortbow", "quantity": 1},
    {"id": 892, "name": "Rune arrow", "quantity": 900},
    {"id": 10499, "name": "Ava's accumulator", "quantity": 1},
    {"id": 2503, "name": "Black d'hide body", "quantity": 1},
    {"id": 2497, "name": "Black d'hide chaps", "quantity": 1},
    {"id": 1215, "name": "Dragon dagger", "quantity": 1},
    {"id": 8013, "name": "Teleport to house", "quantity": 50},
    {"id": 2353, "name": "Steel bar", "quantity": 120},
    {"id": 1519, "name": "Willow logs", "quantity": 400},
    {"id": 314, "name": "Feather", "quantity": 3000},
    {"id": 995, "name": "Coins", "quantity": 2500000},
]


def big_bank():
    """Distinct real item names past BANK_INLINE_LIMIT (200), so routing
    summarizes. ids are 0: nothing downstream of the eval reads them."""
    items = [dict(it) for it in bank]
    herbs = ["Guam leaf", "Marrentill", "Tarromin", "Harralander",
             "Ranarr weed", "Toadflax", "Irit leaf", "Avantoe", "Kwuarm",
             "Snapdragon", "Cadantine", "Lantadyme", "Dwarf weed", "Torstol"]
    potions = ["Attack potion", "Strength potion", "Defence potion",
               "Ranging potion", "Magic potion", "Energy potion",
               "Antipoison", "Antifire potion", "Stamina potion",
               "Agility potion", "Fishing potion", "Hunter potion"]
    logs = ["Logs", "Oak logs", "Teak logs", "Maple logs", "Mahogany logs",
            "Yew logs", "Magic logs", "Redwood logs"]
    ores = ["Copper ore", "Tin ore", "Iron ore", "Silver ore", "Coal",
            "Gold ore", "Mithril ore", "Adamantite ore", "Runite ore"]
    bars = ["Bronze bar", "Iron bar", "Silver bar", "Gold bar",
            "Mithril bar", "Adamantite bar", "Runite bar"]
    gems = ["Uncut sapphire", "Uncut emerald", "Uncut ruby", "Uncut diamond",
            "Sapphire", "Emerald", "Ruby", "Diamond"]
    food = ["Lobster", "Swordfish", "Monkfish", "Karambwan", "Anglerfish",
            "Tuna", "Bass", "Sea turtle", "Manta ray"]
    for h in herbs:
        items.append({"id": 0, "name": "Grimy " + h.lower(), "quantity": 25})
        items.append({"id": 0, "name": h, "quantity": 25})
    for p in potions:
        for dose in (1, 2, 3, 4):
            items.append({"id": 0, "name": p + "(%d)" % dose, "quantity": 5})
    for group in (logs, ores, bars, gems, food):
        for n in group:
            items.append({"id": 0, "name": n, "quantity": 100})
    seeds = ["Potato seed", "Onion seed", "Cabbage seed", "Tomato seed",
             "Sweetcorn seed", "Strawberry seed", "Watermelon seed",
             "Ranarr seed", "Toadflax seed", "Snapdragon seed",
             "Willow seed", "Maple seed", "Yew seed", "Magic seed",
             "Acorn", "Banana tree seed", "Papaya tree seed",
             "Palm tree seed", "Calquat tree seed", "Spirit seed"]
    for s in seeds:
        items.append({"id": 0, "name": s, "quantity": 12})
    ammo = ["Bronze arrow", "Iron arrow", "Steel arrow", "Mithril arrow",
            "Adamant arrow", "Bronze dart", "Iron dart", "Steel dart",
            "Mithril dart", "Adamant dart", "Rune dart", "Bronze bolts",
            "Iron bolts", "Steel bolts", "Mithril bolts", "Adamant bolts",
            "Runite bolts"]
    remains = ["Bones", "Big bones", "Babydragon bones", "Dragon bones",
               "Wyvern bones", "Ensouled goblin head", "Ensouled giant head"]
    fish_raw = ["Raw lobster", "Raw swordfish", "Raw monkfish", "Raw shark",
                "Raw anglerfish", "Raw karambwan", "Raw tuna", "Raw bass"]
    hides = ["Cowhide", "Green dragonhide", "Blue dragonhide",
             "Red dragonhide", "Black dragonhide", "Green dragon leather",
             "Snake hide"]
    for group in (ammo, remains, fish_raw, hides):
        for n in group:
            items.append({"id": 0, "name": n, "quantity": 60})
    return items


def capture(account_type, bank_items):
    return {
        "playerName": "Sample Adventurer",
        "combatLevel": 100,
        "location": {"plane": 0, "regionId": 12850, "x": 3222, "y": 3218},
        "accountType": account_type,
        "skills": skills,
        "skillXp": skill_xp,
        "boostsOrDrains": {},
        "questStates": quest_states,
        "diaries": {
            "Ardougne": ["easy"], "Desert": [], "Falador": ["easy", "medium"],
            "Fremennik": [], "Kandarin": ["easy"], "Karamja": ["easy"],
            "Kourend & Kebos": [], "Lumbridge & Draynor": ["easy", "medium"],
            "Morytania": [], "Varrock": ["easy"], "Western Provinces": [],
            "Wilderness": [],
        },
        "slayerTask": {"creature": "Bloodveld", "remaining": 55},
        "unlocks": {
            "active_spellbook": "standard",
            "rigour_unlocked": False,
            "augury_unlocked": False,
            "preserve_unlocked": False,
        },
        "inventory": [
            {"id": 8013, "name": "Teleport to house", "quantity": 1},
            {"id": 995, "name": "Coins", "quantity": 50000},
        ],
        "equipment": [],
        "bank": bank_items,
        "recentEvents": [],
    }


NORMAL, IRONMAN = 0, 1
fixtures = {
    "eval/snapshot-sample.json": capture(NORMAL, bank),
    "eval/snapshot-sample-ironman.json": capture(IRONMAN, bank),
    "eval/snapshot-sample-bigbank.json": capture(NORMAL, big_bank()),
}
for path, data in fixtures.items():
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
    print("written:", path, "-", len(data["bank"]), "bank items,",
          len(quest_states), "quests")
