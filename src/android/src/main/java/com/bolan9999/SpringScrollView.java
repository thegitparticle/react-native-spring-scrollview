package com.bolan9999;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.support.annotation.NonNull;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.View;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.touch.OnInterceptTouchEventListener;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.uimanager.events.NativeGestureUtil;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.facebook.react.views.scroll.ReactScrollViewHelper;
import com.facebook.react.views.view.ReactViewGroup;

public class SpringScrollView extends ReactViewGroup implements View.OnTouchListener, View.OnLayoutChangeListener, OnInterceptTouchEventListener {
    private float lastX, lastY, height;
    private float contentHeight, refreshHeaderHeight, loadingFooterHeight;
    private boolean momentumScrolling, bounces, scrollEnabled;
    private VelocityTracker tracker;
    private DecelerateAnimation innerAnimation, outerAnimation, reboundAnimation, scrollToAnimation, refreshAnimation, mLoadingAnimation;
    private String refreshStatus, loadingStatus;
    private Offset contentOffset, initContentOffset;
    private EdgeInsets contentInsets;

    public SpringScrollView(@NonNull Context context) {
        super(context);
        refreshStatus = loadingStatus = "waiting";
        initContentOffset = new Offset();
        contentOffset = new Offset();
        contentInsets = new EdgeInsets();
        setClipChildren(false);
    }

    @Override
    protected void onAttachedToWindow() {
        setOnTouchListener(this);
        addOnLayoutChangeListener(this);
        setOnInterceptTouchEventListener(this);
        View child = getChildAt(0);
        if (child != null) {
            if (initContentOffset.y != 0) setOffsetY(initContentOffset.y);
            child.addOnLayoutChangeListener(this);
        }
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        setOnTouchListener(null);
        removeOnLayoutChangeListener(this);
        setOnInterceptTouchEventListener(null);
        View child = getChildAt(0);
        if (child != null) {
            child.removeOnLayoutChangeListener(this);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void onLayoutChange(View view, int i, int i1, int i2, int i3, int i4, int i5, int i6, int i7) {
        if (this == view) {
            setLayoutHeight(i3 - i1, contentHeight);
        } else {
            setLayoutHeight(height, i3 - i1);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(ViewGroup v, MotionEvent ev) {
        int action = ev.getAction() & MotionEvent.ACTION_MASK;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                onDown(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (ev.getX() == lastX && ev.getY() == lastY) return false;
                NativeGestureUtil.notifyNativeGestureStarted(this, ev);
                ReactScrollViewHelper.emitScrollBeginDragEvent(this);
                return true;
        }
        return false;
    }

    @Override
    public boolean onTouch(View view, MotionEvent evt) {
        switch (evt.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE:
                onMove(evt);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                onUp(evt);
                break;
        }
        return true;
    }

    private void onDown(MotionEvent evt) {
        lastX = evt.getX();
        lastY = evt.getY();
        cancelAllAnimations();
        if (momentumScrolling) {
            momentumScrolling = false;
            sendEvent("onMomentumScrollEnd", null);
        }
        sendEvent("onTouchBegin", null);
        tracker = VelocityTracker.obtain();
    }

    private void onMove(MotionEvent evt) {
        if (!scrollEnabled) return;
        drag(evt.getX() - lastX, evt.getY() - lastY);
        lastX = evt.getX();
        lastY = evt.getY();
        tracker.addMovement(evt);
    }

    private void onUp(MotionEvent evt) {
        this.onMove(evt);
        tracker.computeCurrentVelocity(1);
        float vy = tracker.getYVelocity();
        tracker.clear();
        sendEvent("onTouchEnd", null);
        if (!momentumScrolling) {
            momentumScrolling = true;
            sendEvent("onMomentumScrollBegin", null);
        }
        if (shouldRefresh()) {
            refreshStatus = "refreshing";
            contentInsets.top = refreshHeaderHeight;
        }
        if (shouldLoad()) {
            loadingStatus = "loading";
            contentInsets.bottom = loadingFooterHeight;
        }
        if (!scrollEnabled) return;
        if (hitEdgeY()) {
            beginOuterAnimation(vy);
        } else {
            beginInnerAnimation(vy);
        }
    }

    private void beginOuterAnimation(float initialVelocity) {
        if (Math.abs(initialVelocity) < 0.1f) {
            beginReboundAnimation();
            return;
        }
        if (initialVelocity > 15) initialVelocity = 15;
        if (initialVelocity < -15) initialVelocity = -15;
        outerAnimation = new DecelerateAnimation(initialVelocity, 0.9f) {
            @Override
            void onEnd() {
                beginReboundAnimation();
            }
        };
        outerAnimation.start();
    }

    private void beginInnerAnimation(final float initialVelocity) {
        if (Math.abs(initialVelocity) < 0.1f) {
            if (momentumScrolling) {
                momentumScrolling = false;
                sendEvent("onMomentumScrollEnd", null);
            }
            return;
        }
        final long beginTimeInterval = System.currentTimeMillis();
        innerAnimation = new DecelerateAnimation(initialVelocity, 0.997f) {
            @Override
            void onEnd() {
                if (momentumScrolling) {
                    momentumScrolling = false;
                    sendEvent("onMomentumScrollEnd", null);
                }
            }

            @Override
            void onUpdate(float value) {
                super.onUpdate(value);
                if (overshootHead() || overshootFooter()) {
                    long interval = System.currentTimeMillis() - beginTimeInterval;
                    float v = initialVelocity;
                    while (interval-- > 0) {
                        v *= 0.997f;
                    }
                    animator.cancel();
                    beginOuterAnimation(v);
                }
            }
        };
        innerAnimation.start();
    }

    private void beginReboundAnimation() {
        if (!hitEdgeY()) {
            return;
        }
        float endValue;
        if (overshootHead()) {
            endValue = contentInsets.top;
        } else {
            endValue = height - contentHeight - contentInsets.bottom;
        }
        reboundAnimation = new DecelerateAnimation(contentOffset.y, endValue, 500) {
            @Override
            void onEnd() {
                if (momentumScrolling) {
                    momentumScrolling = false;
                    sendEvent("onMomentumScrollEnd", null);
                }
            }
        };
        reboundAnimation.start();
    }

    private void cancelAllAnimations() {
        if (innerAnimation != null) {
            innerAnimation.cancel();
            innerAnimation = null;
        }
        if (outerAnimation != null) {
            outerAnimation.cancel();
            outerAnimation = null;
        }
        if (reboundAnimation != null) {
            reboundAnimation.cancel();
            reboundAnimation = null;
        }
        if (scrollToAnimation != null) {
            scrollToAnimation.cancel();
            scrollToAnimation = null;
        }
        if (refreshAnimation != null) {
            refreshAnimation.cancel();
            refreshAnimation = null;
        }
        if (mLoadingAnimation != null) {
            mLoadingAnimation.cancel();
            mLoadingAnimation = null;
        }
    }


    private void drag(float x, float y) {
        y *= getDampingCoefficient();
        moveToOffsetY(contentOffset.y + y);
    }

    private float getDampingCoefficient() {
        if (!hitEdgeY()) {
            return 1;
        }
        float overshoot = overshootHead() ? contentOffset.y : height - contentHeight - contentOffset.y;
        float c = 0.8f;
        return c / (height * height) * (overshoot * overshoot) - 2 * c / height * overshoot + c;
    }

    private void moveToOffsetY(float y) {
        if (!scrollEnabled) return;
        if (!bounces) {
            if (y > contentInsets.top) y = contentInsets.top;
            if (y < height - contentHeight - contentInsets.bottom)
                y = height - contentHeight - contentInsets.bottom;
        }
        if (contentOffset.y == y) return;
        if (shouldPulling()) {
            refreshStatus = "pulling";
        } else if (shouldPullingEnough()) {
            refreshStatus = "pullingEnough";
        } else if (shouldPullingCancel()) {
            refreshStatus = "pullingCancel";
        } else if (shouldWaiting()) {
            refreshStatus = "waiting";
        }
        if (shouldDragging()) {
            loadingStatus = "dragging";
        } else if (shouldDraggingEnough()) {
            loadingStatus = "draggingEnough";
        } else if (shouldDraggingCancel()) {
            loadingStatus = "draggingCancel";
        } else if (shouldFooterWaiting()) {
            loadingStatus = "waiting";
        }
        setOffsetY(y);
    }

    public void setOffsetY(float y) {
        contentOffset.y = y;
        View child = getChildAt(0);
        if (child != null) child.setTranslationY(contentOffset.y);
        WritableMap event = Arguments.createMap();
        WritableMap contentOffsetMap = Arguments.createMap();
        contentOffsetMap.putDouble("x", -PixelUtil.toDIPFromPixel(contentOffset.x));
        contentOffsetMap.putDouble("y", -PixelUtil.toDIPFromPixel(contentOffset.y));
        event.putMap("contentOffset", contentOffsetMap);
        event.putString("refreshStatus", refreshStatus);
        event.putString("loadingStatus", loadingStatus);
        sendOnScrollEvent(event);
    }

    private boolean hitEdgeY() {
        return overshootHead() || overshootFooter();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        View child = getChildAt(0);
        assert child != null;
        float contentHeight = child.getMeasuredHeight();
        setLayoutHeight(getHeight(), contentHeight);
    }

    private void setLayoutHeight(float height, float contentHeight) {
        if (this.height != height || this.contentHeight != contentHeight) {
            this.height = height;
            this.contentHeight = contentHeight;
            if (this.contentHeight < this.height) this.contentHeight = this.height;
        }
    }

    public void setRefreshHeaderHeight(float height) {
        refreshHeaderHeight = height;
    }

    public void setLoadingFooterHeight(float height) {
        loadingFooterHeight = height;
    }

    private void sendOnScrollEvent(WritableMap event) {
        if (event == null) event = Arguments.createMap();
        EventDispatcher eventDispatcher = ((ReactContext) getContext())
                .getNativeModule(UIManagerModule.class)
                .getEventDispatcher();
        eventDispatcher.dispatchEvent(ScrollEvent.obtain(getId(), "onScroll", event));
    }

    private void sendEvent(String evtName, WritableMap event) {
        if (event == null) event = Arguments.createMap();
        ((ReactContext) getContext()).getJSModule(RCTEventEmitter.class).receiveEvent(getId(), evtName, event);
    }

    public void endRefresh() {
        if (!refreshStatus.equals("refreshing")) return;
        refreshStatus = "rebound";
        refreshAnimation = new DecelerateAnimation(contentOffset.y, 0, 500) {
            @Override
            void onEnd() {
                contentInsets.top = 0;
            }
        };
        refreshAnimation.start();
    }

    public void endLoading() {
        if (!loadingStatus.equals("loading")) return;
        loadingStatus = "rebound";
        mLoadingAnimation = new DecelerateAnimation(contentOffset.y, height - contentHeight, 500) {
            @Override
            void onEnd() {
                contentInsets.bottom = 0;
            }
        };
        mLoadingAnimation.start();
    }

    public void setAllLoaded(boolean allLoaded) {
        loadingStatus = allLoaded ? "allLoaded" : "waiting";
    }

    public void scrollTo(float x, float y, boolean animated) {
        y = -y;
        cancelAllAnimations();
        if (!animated) {
            moveToOffsetY(y);
            return;
        }
        scrollToAnimation = new DecelerateAnimation(contentOffset.y, y, 500);
        scrollToAnimation.start();
    }

    public void setBounces(boolean bounces) {
        this.bounces = bounces;
    }

    public void setScrollEnabled(boolean scrollEnabled) {
        this.scrollEnabled = scrollEnabled;
    }

    public void setInitContentOffset(float x, float y) {
        initContentOffset.x = x;
        initContentOffset.y = y;
    }

    private boolean overshootHead() {
        return contentOffset.y > contentInsets.top;
    }

    private boolean overshootRefresh() {
        return contentOffset.y > contentInsets.top + refreshHeaderHeight;
    }

    private boolean overshootFooter() {
        return contentOffset.y < height - contentHeight;
    }

    private boolean overshootLoading() {
        return contentOffset.y < height - contentHeight - loadingFooterHeight;
    }

    private boolean shouldPulling() {
        return refreshHeaderHeight > 0 && overshootHead() &&
                (refreshStatus.equals("waiting") || refreshStatus.equals("pullingCancel"));
    }

    private boolean shouldPullingEnough() {
        return refreshHeaderHeight > 0 && overshootRefresh() &&
                refreshStatus.equals("pulling");
    }

    private boolean shouldRefresh() {
        return refreshHeaderHeight > 0 && overshootRefresh() && refreshStatus.equals("pullingEnough");
    }

    private boolean shouldPullingCancel() {
        return refreshHeaderHeight > 0 && refreshStatus.equals("pullingEnough")
                && overshootHead() && !overshootRefresh();
    }

    private boolean shouldWaiting() {
        return refreshHeaderHeight > 0 && !overshootHead() &&
                (refreshStatus.equals("rebound") || refreshStatus.equals("pullingCancel"));
    }

    private boolean shouldDragging() {
        return loadingFooterHeight > 0 && overshootFooter() &&
                (loadingStatus.equals("waiting") || loadingStatus.equals("draggingCancel"));
    }

    private boolean shouldDraggingEnough() {
        return loadingFooterHeight > 0 && overshootLoading() && loadingStatus.equals("dragging");
    }

    private boolean shouldLoad() {
        return loadingFooterHeight > 0 && overshootLoading() && loadingStatus.equals("draggingEnough");
    }

    private boolean shouldDraggingCancel() {
        return loadingFooterHeight > 0 && loadingStatus.equals("draggingEnough") &&
                overshootFooter() && !overshootLoading();
    }

    private boolean shouldFooterWaiting() {
        return loadingFooterHeight > 0 && !overshootFooter() &&
                (loadingStatus.equals("rebound") || loadingStatus.equals("draggingCancel"));
    }

    private class DecelerateAnimation {
        protected ValueAnimator animator;

        public DecelerateAnimation(float initialVelocity, float dampingCoefficient) {
            float v = initialVelocity;
            int duration = 0;
            float displacement = 0;
            while (Math.abs(v) > 0.1f) {
                displacement += v;
                v *= dampingCoefficient;
                duration++;
            }
            animator = ValueAnimator.ofFloat(contentOffset.y, contentOffset.y + displacement);
            animator.setDuration(duration);
        }

        public DecelerateAnimation(float from, float to, long duration) {
            animator = ValueAnimator.ofFloat(from, to);
            animator.setDuration(duration);
        }

        public void start() {
            animator.setInterpolator(new DecelerateInterpolator(1.5f));
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animator) {
                    onUpdate((float) animator.getAnimatedValue());
                }
            });
            animator.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    onEnd();
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                }
            });
            animator.start();
        }

        public void cancel() {
            animator.cancel();
        }

        void onEnd() {

        }

        void onUpdate(float value) {
            moveToOffsetY(value);
        }
    }
}
