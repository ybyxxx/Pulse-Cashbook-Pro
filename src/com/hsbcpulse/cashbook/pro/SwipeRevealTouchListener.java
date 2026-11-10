package com.hsbcpulse.cashbook.pro;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.BaseAdapter;
import android.widget.ListView;

/**
 * 列表行滑动揭示触摸监听（微信式）。
 *
 * 自右向左滑动：前景卡片左移露出底部「删除」按钮，松手自动吸合（开/关）。
 * 点击删除按钮才执行删除；点击已展开行的其余区域则收起。
 * 日期头（isEnabled=false）不可滑动。
 */
public class SwipeRevealTouchListener implements View.OnTouchListener {

    private final ListView listView;
    private final int touchSlop;
    private final int animTime = 150;

    private float downX, downY;
    private View fg;              // 当前手势操作的前景卡片
    private View btn;             // 对应的删除按钮（取宽度）
    private float startTx;
    private boolean dragging;
    private View openRow;         // 当前处于展开状态的前景
    private View openButton;      // 展开行的删除按钮（滑开后才可点击）

    public SwipeRevealTouchListener(ListView listView) {
        this.listView = listView;
        this.touchSlop = ViewConfiguration.get(listView.getContext()).getScaledTouchSlop();
    }

    /** 收起当前展开的行（如需强制关闭时调用）。 */
    public void closeOpenRow() {
        if (openRow != null) {
            openRow.animate().translationX(0f).setDuration(animTime).start();
            openRow = null;
        }
        if (openButton != null) {
            openButton.setClickable(false);
            openButton = null;
        }
    }

    /** 供外部判断该行是否处于展开状态。 */
    public static boolean isRevealed(View itemView) {
        if (itemView == null) return false;
        View f = itemView.findViewById(R.id.layout_foreground);
        return f != null && f.getTranslationX() < -1f;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int pos = listView.pointToPosition((int) event.getX(), (int) event.getY());
                if (pos == ListView.INVALID_POSITION) return false;
                BaseAdapter adapter = (BaseAdapter) listView.getAdapter();
                if (adapter == null || pos >= adapter.getCount() || !adapter.isEnabled(pos)) return false;
                View row = listView.getChildAt(pos - listView.getFirstVisiblePosition());
                if (row == null) return false;
                fg = row.findViewById(R.id.layout_foreground);
                btn = row.findViewById(R.id.btn_row_delete);
                if (fg == null) return false;

                // 若其它行正展开，先收起
                if (openRow != null && openRow != fg) {
                    closeOpenRow();
                }
                downX = event.getX();
                downY = event.getY();
                startTx = fg.getTranslationX();
                dragging = false;
                return false; // 保留点击
            }
            case MotionEvent.ACTION_MOVE: {
                if (fg == null) return false;
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 2) {
                    // 仅允许自右向左发起；已展开时允许向右拖回
                    if (dx < 0 || startTx < -1f) {
                        dragging = true;
                        listView.requestDisallowInterceptTouchEvent(true);
                        // 取消 ListView 的长按定时器，避免滑动过程中误触发长按
                        listView.cancelLongPress();
                        if (fg != null) fg.cancelLongPress();
                        listView.setPressed(false);
                        fg.setPressed(false);
                    }
                }
                if (dragging) {
                    float maxSwipe = btn != null ? btn.getWidth() : dp(80);
                    float tx = startTx + dx;
                    if (tx > 0) tx = 0;
                    if (tx < -maxSwipe) tx = -maxSwipe;
                    fg.setTranslationX(tx);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragging && fg != null) {
                    final View target = fg;
                    final View targetBtn = btn;
                    float maxSwipe = targetBtn != null ? targetBtn.getWidth() : dp(80);
                    boolean reveal = target.getTranslationX() < -maxSwipe / 2f;
                    target.animate()
                            .translationX(reveal ? -maxSwipe : 0f)
                            .setDuration(animTime)
                            .start();
                    if (reveal) {
                        openRow = target;
                        openButton = targetBtn;
                        // 滑开后按钮才接收触摸点击
                        if (targetBtn != null) targetBtn.setClickable(true);
                    } else {
                        if (targetBtn != null) targetBtn.setClickable(false);
                        if (openRow == target) {
                            openRow = null;
                            openButton = null;
                        }
                    }
                    dragging = false;
                    fg = null;
                    btn = null;
                    return true;
                }
                dragging = false;
                fg = null;
                btn = null;
                return false;
            }
            default:
                return false;
        }
    }

    private int dp(int v) {
        return Math.round(v * listView.getResources().getDisplayMetrics().density);
    }
}
