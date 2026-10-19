# SourcePawn for JetBrains IDEs

SourcePawn support for CLion (and the other commercial JetBrains IDEs), built
on the [SourcePawn Studio](https://github.com/Sarrus1/sourcepawn-studio)
language server. Made for [Source2Mod](https://github.com/Source2Mod), works for
plain SourceMod plugins too.

## Features

| Area | What you get |
|---|---|
| Files | `.sp` / `.inc` file types with icons, highlighting (own lexer + the server's semantic tokens), brace matching, `//` and `/* */` commenting, quote pairing, a color settings page |
| Language server | completion, hover docs, go to definition, find usages, semantic highlighting (IDE's LSP API); rename, parameter info (Ctrl+P), structure view (this plugin, over the same server) |
| spcomp | from settings → `$SPCOMP` → `$SOURCE2MOD_DIR/scripting` / `$SOURCEMOD_DIR/scripting` → `PATH` |
| Includes | settings list (`$VAR$`, `${VAR}`, `$PROJECT_DIR$`, project-relative) + `$SOURCEPAWN_INCLUDE` + `$SOURCE2MOD_SDK/public/scripting` + the `include` dir next to spcomp |
| Build system (optional) | *Build system: Auto / CMake / AMBuild* reads the spcomp command lines a configured CMake build dir (`build.ninja`, `CMakeFiles/*/build.make`) or AMBuild objdir (`.ambuild2/graph`) already holds: spcomp and the include dirs then come from the build scripts, no settings needed. Explicit settings still win. *None* turns it off |
| Doctor | Settings → Languages & Frameworks → SourcePawn → *Resolve* shows every resolved path and where it came from |
| Run configuration | "SourcePawn" type in the toolbar selector. **Build** (the hammer next to the selector / Ctrl+F9 while it is selected; CLion's own hammer only knows CMake-style targets) compiles into the Build tool window with clickable errors; **Run** compiles, then optionally copies the `.smx` to a server's plugins dir and sends RCON commands (e.g. `s2m reload`) |
| Editor checks | spcomp on save; its errors and warnings show in the editor until the next compile |
| WSL | projects under `\\wsl.localhost\<distro>\...` run spcomp and the server inside the distro (`wsl.exe --exec`); paths and URIs are translated both ways |

Requires a JetBrains IDE **2024.3+ with the LSP API** (CLion, IntelliJ IDEA
Ultimate, ...). Free IDEs (IntelliJ IDEA Community, PyCharm Community) lack it;
supporting them would need a second LSP backend on top of
[LSP4IJ](https://github.com/redhat-developer/lsp4ij).

## Setup

1. Install the plugin (`build/distributions/sourcepawn-jetbrains-*.zip`,
   Settings → Plugins → ⚙ → Install Plugin from Disk).
2. Open Settings → Languages & Frameworks → SourcePawn, set what the
   environment doesn't give, press *Resolve*.
3. The language server: leave *Server binary* empty to use `PATH` or the
   downloaded copy; the IDE offers the download (GitHub release
   `v8.1.8`) when none is found. The prebuilt Linux build needs glibc 2.32+:
   on Ubuntu 20.04 build it from source (`cargo build --release`) and point
   *Server binary* at it.

### Environment variables

| Variable | Used for |
|---|---|
| `SPCOMP` | full path to spcomp |
| `SOURCE2MOD_DIR` / `SOURCEMOD_DIR` | an `addons/source2mod` or `addons/sourcemod` dir; `scripting/spcomp` and `scripting/include` |
| `SOURCE2MOD_SDK` | adds `public/scripting` to the includes |
| `SOURCEPAWN_INCLUDE` | extra include dirs (`:`-separated in WSL / on Linux, `;` on Windows) |

In WSL mode they are read from a login shell in the distro (`bash -lic env`),
so exports in `~/.profile` or `~/.bashrc` count.

### Building through CMake / AMBuild

A SourcePawn run configuration with *Build with the project's CMake / AMBuild
build* runs `cmake --build <dir> [--target <t>]` or `ambuild <objdir>` instead
of spcomp. Its output still goes to the Build window with clickable spcomp
messages, and Run still copies the `.smx` (the script field picks which one;
empty = all) and sends RCON commands. The build dir must have been configured
once (CLion's CMake profile, `configure.py` for AMBuild); a reconfigure that
needs environment variables such as `SOURCE2MOD_SDK` only sees what the
target's login shell exports. CLion's own CMake run configurations keep working
alongside: the plugin only reads their build dirs.

## Notes

- **spcomp on save is done by the plugin, not by the server.** sourcepawn-studio
  always passes `--syntax-only`, and the SourcePawn 2.0 compiler stops before
  semantic analysis with that flag (no "undefined symbol" errors). The server
  also only sets its checks up for files it had read at startup.
- **No TextMate grammar is bundled.** IntelliJ's TextMate highlighting only
  applies to its own TextMate file type, so a real `.sp` file type (icon, brace
  matching, commenter, run configurations) and the grammar don't mix; the
  lexer covers keywords, literals, comments and preprocessor lines, the server's
  semantic tokens the rest.

## Building

```bash
./gradlew buildPlugin
```

```bash
./gradlew runIde
```

Set `platformLocalPath` (in `~/.gradle/gradle.properties`) to an installed
CLion to skip downloading one; `-PrunIdeProject=<dir>` opens a project
directly (`//wsl.localhost/<distro>/...` works, a `\` path loses a backslash
on the way and opens LightEdit instead). `./gradlew test` runs unit tests plus an end-to-end
test against a WSL distro (`-Dsp.test.distro`, `-Dsp.test.spcomp`); it skips
itself when that setup is missing.

## Credits

- [SourcePawn Studio](https://github.com/Sarrus1/sourcepawn-studio) by Sarrus
  (MIT): the language server this plugin drives, and the settings it takes.
- Keyword lists follow the SourcePawn compiler
  ([alliedmodders/sourcepawn](https://github.com/alliedmodders/sourcepawn)).

## Later

- New-plugin templates (Source2Mod / SourceMod `myinfo`)
- clang-format formatting (like the VS Code extension's `formatterSettings`)
- SFTP upload after compile
- Call hierarchy (needs PSI-backed hierarchy providers)
- Marketplace publishing + CI
