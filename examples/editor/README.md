# Editor walkthrough

Run `./tools/lyra-editor.sh examples/editor` from the repository root, or `./lyra-editor examples/editor` from an extracted editor distribution (`lyra-editor.cmd examples\editor` on Windows).

1. Open `main.lyra`. Select `main` in Definitions and click **Set entry**. Accept `Array<String>[]` as its argument.
2. Press **F5** to run. The program prints `41` and returns `0`. Its definitions stay in the REPL.
3. Enter `::greet["Lyra"]` in the bottom REPL, then enter `visits`. Repeat to see retained state.
4. Select `calculate`, choose **Debug**, and supply `20`. Use **F7** to step into `math->::twice`, **F8** to step over, and **F9** to continue.
5. Click a line gutter to add a breakpoint. **Stop** ends the debug process and enables editing again.
6. Edit a definition, then use **Ctrl+Enter** to evaluate it in the current REPL, or **F5** to start a fresh program.

The editor run target can be any top-level function. Building an executable JAR still requires Lyra's public `main :Fn<Array<String>;I32>` contract.
