LevelledMobs currently has poor support for Folia. This branch aims to better support Folia and does not guarantee compatibility with Bukkit, Paper, and older versions, until the official version is finally available for Folia.

Downloads see [Actions](https://github.com/KujouMolean/LevelledMobs/actions), don't forget install together with FoliaAdapter.

Implemented:
 - kill all command function
 - force-all command function
 - replace incomprehensible Scheduler with Folia dedicated Adapter

Fixed:
 - ConcurrentModificationException while update nametag
 - Async getEntities call while update nametag
 - NullPointException while schedule a task
 - Maybe more?
