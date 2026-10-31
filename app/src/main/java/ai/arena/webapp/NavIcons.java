package ai.arena.webapp;

/**
 * Tab id -> nav icon, as a lookup table instead of an array.
 *
 * This used to be a plain {@code int[]} indexed by tab id. Tab ids are not dense and
 * change as entries move around (Update moved off the nav and back again), so
 * indexing a short array by tab id threw ArrayIndexOutOfBoundsException inside
 * MainActivity.onCreate and the app closed instantly on launch. A switch cannot go
 * out of bounds.
 */
public final class NavIcons {

    private NavIcons() {
    }

    public static int forTab(int tab) {
        switch (tab) {
            case AppUi.TAB_LEADERBOARD:
                return R.drawable.ic_nav_leaderboard;
            case AppUi.TAB_SEARCH:
                return R.drawable.ic_nav_search;
            case AppUi.TAB_UPDATE:
                return R.drawable.ic_nav_update;
            case AppUi.TAB_MORE:
                return R.drawable.ic_nav_more;
            case AppUi.TAB_CHAT:
            default:
                return R.drawable.ic_nav_chat;
        }
    }
}
