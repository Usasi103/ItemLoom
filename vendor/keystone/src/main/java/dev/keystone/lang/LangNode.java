package dev.keystone.lang;

import java.util.List;

/** One lang entry: a single line (null text = send nothing) or several lines sent one by one. */
public sealed interface LangNode permits LangNode.Text, LangNode.Lines {

    record Text(String text) implements LangNode {}

    record Lines(List<Text> lines) implements LangNode {
        public Lines {
            lines = List.copyOf(lines);
        }
    }
}
