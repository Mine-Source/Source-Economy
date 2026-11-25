package com.economycore.gui;

import com.economycore.core.EconomyCore;
import com.economycore.core.Msg;
import com.economycore.core.Sql;
import com.economycore.core.Tasks;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Entity editor: fields (prompt + save), toggles and custom actions. After every save the caller's reload runs. */
public final class EditMenu extends Menu {
    public record Field(Material icon, String label, String value, String prompt, Function<String, CompletableFuture<?>> save) {}
    public record Toggle(Material icon, String label, boolean on, Supplier<CompletableFuture<?>> flip) {}
    public record Action(Material icon, String label, List<String> lore, Consumer<ClickInfo> run) {}

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private final List<Field> fields = new ArrayList<>();
    private final List<Toggle> toggles = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    private final Runnable reload;

    public EditMenu(EconomyCore pl, Player p, String title, Runnable reload) {
        super(pl, p, title, 6);
        this.reload = reload;
    }

    public EditMenu field(Material icon, String label, String value, String prompt, Function<String, CompletableFuture<?>> save) {
        fields.add(new Field(icon, label, value, prompt, save));
        return this;
    }

    public EditMenu toggle(Material icon, String label, boolean on, Supplier<CompletableFuture<?>> flip) {
        toggles.add(new Toggle(icon, label, on, flip));
        return this;
    }

    public EditMenu action(Material icon, String label, List<String> lore, Consumer<ClickInfo> run) {
        actions.add(new Action(icon, label, lore, run));
        return this;
    }

    @Override public EditMenu back(Runnable parent) { this.parent = parent; return this; }

    public static CompletableFuture<Void> update(String sql, Object... params) {
        return EconomyCore.get().db().tx(tx -> { Sql.exec(tx, sql, params); return null; });
    }

    private void done(Throwable t) {
        Tasks.sync(() -> {
            if (t != null) Msg.error(viewer, t); else Msg.send(viewer, "saved");
            reload.run();
        });
    }

    @Override
    protected void render() {
        fillBorder();
        int i = 0;
        for (Field f : fields) {
            if (i >= SLOTS.length) break;
            set(SLOTS[i++], Items.of(f.icon(), "&e" + f.label(), "&7Actual: &f" + f.value(), "&aClic para editar"), c ->
                    Prompt.ask(viewer, f.prompt() + " &7(actual: &f" + f.value() + "&7)", s ->
                            f.save().apply(s).whenComplete((r, t) -> done(t)), reload));
        }
        for (Toggle t : toggles) {
            if (i >= SLOTS.length) break;
            set(SLOTS[i++], Items.of(t.on() ? Material.LIME_DYE : Material.GRAY_DYE, "&e" + t.label(), t.on() ? "&aActivado" : "&cDesactivado", "&7Clic para cambiar"), c ->
                    t.flip().get().whenComplete((r, e) -> done(e)));
        }
        for (Action a : actions) {
            if (i >= SLOTS.length) break;
            set(SLOTS[i++], Items.of(a.icon(), a.label(), a.lore()), a.run());
        }
        nav(49);
    }
}
