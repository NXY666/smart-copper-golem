# Smart Copper Golem

English | [简体中文](README-zh_CN.md) | [繁體中文](README-zh_TW.md)

Mojang has been extremely cautious in "benefiting players," with the Copper Golem being a prime example.

They were designed to only transfer a minimal number of items through simple iteration from one chest to another. Once encountering an item that cannot be stored, they would fall into an endless loop.

Smart Copper Golem aims to endow Copper Golems with more intelligent behavior, enabling them to manage items more efficiently.

## Core Features

### 🧠 Deep Memory

✅ When accessing a chest, the Copper Golem remembers the types of items inside that chest.

✅ When picking up items from the copper chest, the Copper Golem prioritizes selecting items that exist in its memory.

✅ Copper Golems exchange memories with each other, making collective learning more efficient.

### 🎯 Target Selection

✅ Copper Golems can now open barrels and shulker boxes.

✅ New item matching mode "Similar Items," allowing Copper Golems to place similar items (such as wool of different colors, anvils with varying levels of damage, etc.) into the same chest.

✅ When the item in hand cannot be placed into any target chest, the Copper Golem will return the item to the copper chest and ignore that item for a period of time.

✅ Copper Golems no longer aimlessly iterate in sequence; they now follow the following priority strategy:

- Prioritize chests within interaction range that can be interacted with (especially effective for compact item storage systems)
- Prioritize chests in memory associated with that item

### 🗺️ Pathfinding & Interaction

✅ Utilizes a more robust pathfinding solution with higher fault tolerance compared to the vanilla version, capable of handling complex terrain.
✅ More precise interactivity checks; chests that players can interact with, Copper Golems can also interact with.

### ⚙️ Configuration

Some core parameters can be adjusted via the configuration file:

- Interaction range, maximum number of items to carry.
- Item matching mode.
- Search range, memory decay time, ignore duration, etc.

## Cost

This mod enhances the behavior of Copper Golems, improving their item management efficiency, but it also brings additional performance overhead.

If your server needs to build large-scale redstone machines using Copper Golems, please use this mod with caution.

## Configuration Method

The configuration file is located at `config/smart-copper-golem.json`.

- **Method 1: Directly edit the JSON file**
- **Method 2: Visual configuration via ModMenu** (requires installing [ModMenu](https://modrinth.com/mod/modmenu))

## Open Source License

- AGPL-3.0 (see the `LICENSE.txt` file in the project for details)