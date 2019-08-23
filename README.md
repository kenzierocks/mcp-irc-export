mcp-irc-export
==============

_A companion program for [MCPIDE](https://github.com/kenzierocks/MCPIDE)._

`mcp-irc-export` takes a file and sends every line to a DCC connection,
typically MCPBot_Reborn. Many aspects of it can be configued through options,
see `--help` for details.

### Building
For easiest usage, run `./gradlew install`. You can the run the program as
`./build/install/mcp-irc-export/bin/mcp-irc-export`, or move the directory to
wherever you feel comfortable.

### Usage
Simply run `./mcp-irc-export --name yourNick yourInput.txt`. The program will ask
for your password via TTY, then perform the connection steps and send the input.

All communication will be visible via standard out.
