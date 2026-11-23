package com.economycore.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/** Sequential chat prompts collecting several answers. */
public final class Form {
    private final Player p;
    private final List<String[]> steps = new ArrayList<>();
    private final Map<String, String> answers = new LinkedHashMap<>();
    private Runnable cancel;

    private Form(Player p) { this.p = p; }

    public static Form of(Player p) { return new Form(p); }

    public Form ask(String key, String question) { steps.add(new String[]{key, question}); return this; }

    public Form onCancel(Runnable r) { this.cancel = r; return this; }

    public void then(Consumer<Map<String, String>> done) { next(0, done); }

    private void next(int i, Consumer<Map<String, String>> done) {
        if (i >= steps.size()) { done.accept(answers); return; }
        String[] s = steps.get(i);
        Prompt.ask(p, s[1], v -> { answers.put(s[0], v); next(i + 1, done); }, cancel);
    }
}
