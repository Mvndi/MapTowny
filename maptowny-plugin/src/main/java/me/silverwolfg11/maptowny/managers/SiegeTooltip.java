package me.silverwolfg11.maptowny.managers;

import com.gmail.goosius.siegewar.objects.Siege;
import com.gmail.goosius.siegewar.settings.SiegeWarSettings;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.object.Translation;
import me.silverwolfg11.maptowny.objects.MarkerOptions;

import java.util.ArrayList;
import java.util.List;

final class SiegeTooltip {
    private static final String TITLE = "dynmap_siege_title";
    private static final String TOWN = "dynmap_siege_town";
    private static final String TYPE = "dynmap_siege_type";
    private static final String PROGRESS = "dynmap_siege_progress";
    private static final String STATUS = "dynmap_siege_status";
    private static final String BALANCE = "dynmap_siege_balance";
    private static final String CONTROL = "dynmap_siege_banner_control";
    private static final String POINTS = "dynmap_siege_battle_points";
    private static final String TIME = "dynmap_siege_battle_time_left";
    private static final String CHEST = "dynmap_siege_war_chest";
    private static final String OPEN_TITLE = "<b>";
    private static final String CLOSE_TITLE = "</b><hr>";
    private static final String LINE_BREAK = "<br>";
    private static final String AMPERSAND = "&";
    private static final String ESCAPED_AMPERSAND = "&amp;";
    private static final String LESS_THAN = "<";
    private static final String ESCAPED_LESS_THAN = "&lt;";
    private static final String GREATER_THAN = ">";
    private static final String ESCAPED_GREATER_THAN = "&gt;";

    private SiegeTooltip() {
    }

    static MarkerOptions buildOptions(Siege siege) {
        String title = Translation.of(TITLE, siege.getAttackerNameForDisplay(), siege.getDefenderNameForDisplay());
        String popup = OPEN_TITLE + escapeHtml(title) + CLOSE_TITLE
                + String.join(LINE_BREAK, buildLines(siege));
        return MarkerOptions.builder().name(title).hoverTooltip(escapeHtml(title)).clickTooltip(popup).build();
    }

    private static List<String> buildLines(Siege siege) {
        List<String> lines = new ArrayList<>();
        lines.add(translate(TOWN, siege.getTown().getName()));
        lines.add(translate(TYPE, siege.getSiegeType().getName()));
        lines.add(translate(STATUS, siege.getStatus().getName()));
        lines.add(translate(PROGRESS, siege.getNumBattleSessionsCompleted(), SiegeWarSettings.getSiegeDurationBattleSessions()));
        lines.add(translate(BALANCE, siege.getSiegeBalance()));
        lines.add(translate(CONTROL, siege.getBannerControllingSide().getFormattedName().defaultLocale()));
        lines.add(translate(POINTS, siege.getFormattedAttackerBattlePoints(), siege.getFormattedDefenderBattlePoints()));
        lines.add(translate(TIME, siege.getFormattedBattleTimeRemaining()));
        if (TownyEconomyHandler.isActive())
            lines.add(translate(CHEST, TownyEconomyHandler.getFormattedBalance(siege.getWarChestAmount())));
        return lines;
    }

    private static String translate(String key, Object... arguments) {
        return escapeHtml(Translation.of(key, arguments));
    }

    private static String escapeHtml(String text) {
        return text.replace(AMPERSAND, ESCAPED_AMPERSAND)
                .replace(LESS_THAN, ESCAPED_LESS_THAN).replace(GREATER_THAN, ESCAPED_GREATER_THAN);
    }
}
