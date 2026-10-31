package ai.arena.webapp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/**
 * A tiny, dependency-free pull-to-refresh container.
 *
 * Drag down while the inner view (the WebView) is already scrolled to the top and
 * a spinner appears; release past the trigger distance to refresh.
 */
public class PullToRefreshLayout extends FrameLayout {

    public interface OnRefreshListener {
        void onRefresh();
    }

    private OnRefreshListener listener;
    private View target;
    private final ProgressBar indicator;
    private final int triggerPx;
    private final int sizePx;
    private final int marginPx;

    private float downY;
    private float pull;
    private boolean pulling;
    private boolean refreshing;

    public PullToRefreshLayout(Context context) {
        this(context, null);
    }

    public PullToRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        triggerPx = (int) (76 * density);
        sizePx = (int) (34 * density);
        marginPx = (int) (14 * density);

        indicator = new ProgressBar(context);
        indicator.setIndeterminate(true);
        indicator.setVisibility(GONE);

        GradientDrawable bubble = new GradientDrawable();
        bubble.setShape(GradientDrawable.OVAL);
        bubble.setColor(0xE6000000);
        indicator.setBackground(bubble);

        int pad = (int) (2 * density);
        indicator.setPadding(pad, pad, pad, pad);

        LayoutParams lp = new LayoutParams(sizePx, sizePx,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = marginPx;
        indicator.setLayoutParams(lp);
        addView(indicator);

        setClipChildren(false);
    }

    public void setTarget(View view) {
        target = view;
        // The spinner must paint on top of the page content.
        indicator.bringToFront();
    }

    public void setOnRefreshListener(OnRefreshListener l) {
        listener = l;
    }

    public void setRefreshing(boolean value) {
        refreshing = value;
        pull = 0f;
        indicator.setAlpha(1f);
        indicator.setVisibility(value ? VISIBLE : GONE);
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    private boolean canChildScrollUp() {
        return target != null && target.canScrollVertically(-1);
    }

    private int slop() {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8,
                getResources().getDisplayMetrics());
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                pulling = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!refreshing && !canChildScrollUp() && !pulling
                        && ev.getY() - downY > slop() * 2f) {
                    pulling = true;
                    indicator.setVisibility(VISIBLE);
                    indicator.setAlpha(0f);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pulling = false;
                if (!refreshing) indicator.setVisibility(GONE);
                break;
            default:
                break;
        }
        return pulling;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                pull = Math.max(0f, ev.getY() - downY);
                float progress = Math.min(1f, pull / triggerPx);
                indicator.setVisibility(VISIBLE);
                indicator.setAlpha(progress);
                indicator.setScaleX(0.6f + 0.4f * progress);
                indicator.setScaleY(0.6f + 0.4f * progress);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean shouldRefresh = pull >= triggerPx * 0.85f && !refreshing;
                pull = 0f;
                pulling = false;
                indicator.setScaleX(1f);
                indicator.setScaleY(1f);
                if (shouldRefresh && listener != null) {
                    setRefreshing(true);
                    listener.onRefresh();
                } else if (!refreshing) {
                    indicator.setVisibility(GONE);
                }
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }
}
