"""Builds eval/snapshot-sample.json: a synthetic GameCapture fixture safe to
publish. Quest NAMES are copied from a real capture (the resolver matches
quest entities against them -- they are game data, not player data); every
player-specific field (name, levels, bank, states) is invented."""
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

sample = {
    "playerName": "Sample Adventurer",
    "combatLevel": 100,
    "location": {"plane": 0, "regionId": 12850, "x": 3222, "y": 3218},
    "accountType": 0,
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
    "bank": bank,
    "bankCapturedAtMs": 1755400000000,
    "recentEvents": [],
}

with open("eval/snapshot-sample.json", "w", encoding="utf-8") as f:
    json.dump(sample, f, indent=1)
print("written: eval/snapshot-sample.json,",
      len(quest_states), "quests,", len(bank), "bank items")
