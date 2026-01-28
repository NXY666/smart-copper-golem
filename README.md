# Smart Copper Golem

English | [简体中文](README-zh_CN.md) | [繁體中文](README-zh_TW.md)

> This mod is implemented entirely server-side; the client only provides Mod Menu integration.

Mojang has been extremely cautious when it comes to "benefiting players," and the Copper Golem is a prime example.

They were designed to move only a very small number of items from one chest to another with a simple traversal. Once they encounter an item that cannot be inserted, they can fall into an endless loop.

Smart Copper Golem aims to give Copper Golems smarter behavior so they can manage items more efficiently.

## Features

### 🧠 Deep Memory

* When accessing a chest, a Copper Golem remembers the types of items inside.
* When picking up items from the Copper Chest, a Copper Golem prioritizes items that exist in its memory.
* Copper Golems exchange memories with each other, making collective learning more efficient.

### 🎯 Target Selection

* Copper Golems can now open Barrels and Shulker Boxes.
* A new item matching mode, "Similar Items," allows Copper Golems to put similar items (e.g., different-colored wool, anvils with different damage levels, etc.) into the same chest.
* When the item in hand cannot be inserted into any target container, a Copper Golem will return it to the Copper Chest and ignore that item for a period of time.
* Copper Golems no longer cycle through targets aimlessly. They now follow these priority rules:

  * Prefer interactable containers within interaction range (especially effective in compact storage builds)
  * Prefer remembered containers that are relevant to the item

### 🗺️ Pathfinding & Interaction

* Uses a more robust pathfinding approach than vanilla, with higher fault tolerance for complex terrain.
* More accurate interaction checks—if a player can interact with a container, a Copper Golem can too.

### ⚙️ Configuration

Some core parameters can be adjusted via the config file:

* Search range, interaction range.
* Item matching mode. maximum items carried.
* Memory decay time, ignore duration, and more.

## Compatibility

This mod fully reworks Copper Golem behavior, so it may be incompatible with other mods that also modify Copper Golems.

If your server relies on Copper Golems for redstone machines, those machines may not function properly.

## Configuration 

The config file is located at `config/smart-copper-golem.json`.

* **Option 1**: Edit the JSON file directly
* **Option 2**: Configure visually via [Mod Menu](https://modrinth.com/mod/modmenu)

## License

* AGPL-3.0 (see `LICENSE.txt` in the project for details)
