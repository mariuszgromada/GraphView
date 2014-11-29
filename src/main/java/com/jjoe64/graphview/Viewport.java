/**
 * GraphView
 * Copyright (C) 2014  Jonas Gehring
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * with the "Linking Exception", which can be found at the license.txt
 * file in this program.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * with the "Linking Exception" along with this program; if not,
 * write to the author Jonas Gehring <g.jjoe64@gmail.com>.
 */
package com.jjoe64.graphview;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.EdgeEffectCompat;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.OverScroller;

import com.jjoe64.graphview.compat.OverScrollerCompat;
import com.jjoe64.graphview.series.DataPointInterface;
import com.jjoe64.graphview.series.Series;

import java.util.Iterator;
import java.util.List;

/**
 * Created by jonas on 13.08.14.
 */
public class Viewport {
    private final ScaleGestureDetector.OnScaleGestureListener mScaleGestureListener
            = new ScaleGestureDetector.OnScaleGestureListener() {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float viewportWidth = mCurrentViewport.width();
            float center = mCurrentViewport.left + viewportWidth / 2;
            viewportWidth /= detector.getScaleFactor();
            mCurrentViewport.left = center - viewportWidth / 2;
            mCurrentViewport.right = mCurrentViewport.left+viewportWidth;

            // viewportStart must not be < minX
            float minX = (float) getMinX(true);
            if (mCurrentViewport.left < minX) {
                mCurrentViewport.left = minX;
                mCurrentViewport.right = mCurrentViewport.left+viewportWidth;
            }

            // viewportStart + viewportSize must not be > maxX
            float maxX = (float) getMaxX(true);
            if (viewportWidth == 0) {
                mCurrentViewport.right = maxX;
            }
            double overlap = mCurrentViewport.left + viewportWidth - maxX;
            if (overlap > 0) {
                // scroll left
                if (mCurrentViewport.left-overlap > minX) {
                    mCurrentViewport.left -= overlap;
                    mCurrentViewport.right = mCurrentViewport.left+viewportWidth;
                } else {
                    // maximal scale
                    mCurrentViewport.left = minX;
                    mCurrentViewport.right = maxX;
                }
            }

            // adjust viewport, labels, etc.
            mGraphView.onDataChanged(true, false);

            ViewCompat.postInvalidateOnAnimation(mGraphView);

            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            if (mIsScalable) {
                Log.d("Viewport", "onScaleBegin");
                mScalingBeginWidth = mCurrentViewport.width();
                mScalingBeginLeft = mCurrentViewport.left;
                mScalingActive = true;
                return true;
            } else {
                return false;
            }
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            Log.d("Viewport", "onScaleEnd");
            mScalingActive = false;

            // re-adjust
            mXAxisBoundsStatus = AxisBoundsStatus.READJUST_AFTER_SCALE;

            mScrollingReferenceX = Float.NaN;

            // adjust viewport, labels, etc.
            mGraphView.onDataChanged(true, false);

            ViewCompat.postInvalidateOnAnimation(mGraphView);
        }
    };

    private final GestureDetector.SimpleOnGestureListener mGestureListener
            = new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onDown(MotionEvent e) {
            if (!mIsScrollable || mScalingActive) return false;

            // Initiates the decay phase of any active edge effects.
            releaseEdgeEffects();
            mScrollerStartViewport.set(mCurrentViewport);
            // Aborts any active scroll animations and invalidates.
            mScroller.forceFinished(true);
            ViewCompat.postInvalidateOnAnimation(mGraphView);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (!mIsScrollable || mScalingActive) return false;

            Log.d("Viewport", "on Scroll");

            if (Float.isNaN(mScrollingReferenceX)) {
                mScrollingReferenceX = mCurrentViewport.left;
            }

            // Scrolling uses math based on the viewport (as opposed to math using pixels).
            /**
             * Pixel offset is the offset in screen pixels, while viewport offset is the
             * offset within the current viewport. For additional information on surface sizes
             * and pixel offsets, see the docs for {@link computeScrollSurfaceSize()}. For
             * additional information about the viewport, see the comments for
             * {@link mCurrentViewport}.
             */
            float viewportOffsetX = distanceX * mCurrentViewport.width() / mGraphView.getGraphContentWidth();
            float viewportOffsetY = -distanceY * mCurrentViewport.height() / mGraphView.getGraphContentHeight();

            int completeWidth = (int)((mCompleteRange.width()/mCurrentViewport.width()) * (float) mGraphView.getGraphContentWidth());
            int completeHeight = (int)((mCompleteRange.height()/mCurrentViewport.height()) * (float) mGraphView.getGraphContentHeight());

            int scrolledX = (int) (completeWidth
                    * (mCurrentViewport.left + viewportOffsetX - mCompleteRange.left)
                    / mCompleteRange.width());
            int scrolledY = (int) (completeHeight
                    * (mCompleteRange.bottom - mCurrentViewport.bottom - viewportOffsetY)
                    / mCompleteRange.height());
            boolean canScrollX = mCurrentViewport.left > mCompleteRange.left
                    || mCurrentViewport.right < mCompleteRange.right;
            boolean canScrollY = mCurrentViewport.bottom > mCompleteRange.bottom
                    || mCurrentViewport.top < mCompleteRange.top;

            if (canScrollX) {
                if (viewportOffsetX < 0) {
                    float tooMuch = mCurrentViewport.left+viewportOffsetX - mCompleteRange.left;
                    if (tooMuch < 0) {
                        viewportOffsetX -= tooMuch;
                    }
                } else {
                    float tooMuch = mCurrentViewport.right+viewportOffsetX - mCompleteRange.right;
                    if (tooMuch > 0) {
                        viewportOffsetX -= tooMuch;
                    }
                }
                mCurrentViewport.left += viewportOffsetX;
                mCurrentViewport.right += viewportOffsetX;
            }
            if (canScrollY) {
                //mCurrentViewport.top += viewportOffsetX;
                //mCurrentViewport.bottom -= viewportOffsetX;
            }

            if (canScrollX && scrolledX < 0) {
                mEdgeEffectLeft.onPull(scrolledX / (float) mGraphView.getGraphContentWidth());
                mEdgeEffectLeftActive = true;
            }
            if (canScrollY && scrolledY < 0) {
                mEdgeEffectBottom.onPull(scrolledY / (float) mGraphView.getGraphContentHeight());
                mEdgeEffectBottomActive = true;
            }
            if (canScrollX && scrolledX > completeWidth - mGraphView.getGraphContentWidth()) {
                Log.d("Viewport", "hier "+scrolledX+"/"+completeWidth);
                mEdgeEffectRight.onPull((scrolledX - completeWidth + mGraphView.getGraphContentWidth())
                        / (float) mGraphView.getGraphContentWidth());
                mEdgeEffectRightActive = true;
            }
            //if (canScrollY && scrolledY > mSurfaceSizeBuffer.y - mContentRect.height()) {
            //    mEdgeEffectTop.onPull((scrolledY - mSurfaceSizeBuffer.y + mContentRect.height())
            //            / (float) mContentRect.height());
            //    mEdgeEffectTopActive = true;
            //}

            // adjust viewport, labels, etc.
            mGraphView.onDataChanged(true, false);

            ViewCompat.postInvalidateOnAnimation(mGraphView);
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2,
                               float velocityX, float velocityY) {
            //fling((int) -velocityX, (int) -velocityY);
            return true;
        }
    };
    private Paint mPaint;

    public boolean onTouchEvent(MotionEvent event) {
        boolean b = mScaleGestureDetector.onTouchEvent(event);
        b |= mGestureDetector.onTouchEvent(event);
        return b;
    }

    public enum AxisBoundsStatus {
        INITIAL, AUTO_ADJUSTED, READJUST_AFTER_SCALE, FIX
    }

    private final GraphView mGraphView;
    protected RectF mCurrentViewport = new RectF();
    protected RectF mCompleteRange = new RectF();
    protected boolean mScalingActive;
    protected float mScalingBeginWidth;
    protected float mScalingBeginLeft;
    private boolean mIsScrollable;
    private boolean mIsScalable;
    protected GestureDetector mGestureDetector;
    protected ScaleGestureDetector mScaleGestureDetector;

    protected OverScroller mScroller;
    private EdgeEffectCompat mEdgeEffectTop;
    private EdgeEffectCompat mEdgeEffectBottom;
    private EdgeEffectCompat mEdgeEffectLeft;
    private EdgeEffectCompat mEdgeEffectRight;
    private boolean mEdgeEffectTopActive;
    private boolean mEdgeEffectBottomActive;
    private boolean mEdgeEffectLeftActive;
    private boolean mEdgeEffectRightActive;
    private RectF mScrollerStartViewport = new RectF();
    protected float mScrollingReferenceX = Float.NaN;

    private AxisBoundsStatus mXAxisBoundsStatus;
    private AxisBoundsStatus mYAxisBoundsStatus;

    private boolean mXAxisBoundsManual;
    private boolean mYAxisBoundsManual;

    private int mBackgroundColor;

    public Viewport(GraphView graphView) {
        mScroller = new OverScroller(graphView.getContext());
        mEdgeEffectTop = new EdgeEffectCompat(graphView.getContext());
        mEdgeEffectBottom = new EdgeEffectCompat(graphView.getContext());
        mEdgeEffectLeft = new EdgeEffectCompat(graphView.getContext());
        mEdgeEffectRight = new EdgeEffectCompat(graphView.getContext());
        mGestureDetector = new GestureDetector(graphView.getContext(), mGestureListener);
        mScaleGestureDetector = new ScaleGestureDetector(graphView.getContext(), mScaleGestureListener);

        mGraphView = graphView;
        mXAxisBoundsStatus = AxisBoundsStatus.INITIAL;
        mYAxisBoundsStatus = AxisBoundsStatus.INITIAL;
        mBackgroundColor = Color.TRANSPARENT;
        mPaint = new Paint();
    }

    public void setXAxisBoundsStatus(AxisBoundsStatus s) {
        mXAxisBoundsStatus = s;
    }

    public void setYAxisBoundsStatus(AxisBoundsStatus s) {
        mYAxisBoundsStatus = s;
    }

    public boolean isScrollable() {
        return mIsScrollable;
    }

    public void setScrollable(boolean mIsScrollable) {
        this.mIsScrollable = mIsScrollable;
    }

    public AxisBoundsStatus getXAxisBoundsStatus() {
        return mXAxisBoundsStatus;
    }

    public AxisBoundsStatus getYAxisBoundsStatus() {
        return mYAxisBoundsStatus;
    }

    public void calcCompleteRange() {
        List<Series> series = mGraphView.getSeries();
        mCompleteRange.set(0, 0, 0, 0);
        if (!series.isEmpty() && !series.get(0).isEmpty()) {
            double d = series.get(0).getLowestValueX();
            for (Series s : series) {
                if (!s.isEmpty() && d > s.getLowestValueX()) {
                    d = s.getLowestValueX();
                }
            }
            mCompleteRange.left = (float) d;

            d = series.get(0).getHighestValueX();
            for (Series s : series) {
                if (!s.isEmpty() && d < s.getHighestValueX()) {
                    d = s.getHighestValueX();
                }
            }
            mCompleteRange.right = (float) d;

            d = series.get(0).getLowestValueY();
            for (Series s : series) {
                if (!s.isEmpty() && d > s.getLowestValueY()) {
                    d = s.getLowestValueY();
                }
            }
            mCompleteRange.bottom = (float) d;

            d = series.get(0).getHighestValueY();
            for (Series s : series) {
                if (!s.isEmpty() && d < s.getHighestValueY()) {
                    d = s.getHighestValueY();
                }
            }
            mCompleteRange.top = (float) d;
        }

        // calc current viewport bounds
        if (mYAxisBoundsStatus == AxisBoundsStatus.AUTO_ADJUSTED) {
            mYAxisBoundsStatus = AxisBoundsStatus.INITIAL;
        }
        if (mYAxisBoundsStatus == AxisBoundsStatus.INITIAL) {
            mCurrentViewport.top = mCompleteRange.top;
            mCurrentViewport.bottom = mCompleteRange.bottom;
        }

        if (mXAxisBoundsStatus == AxisBoundsStatus.AUTO_ADJUSTED) {
            mXAxisBoundsStatus = AxisBoundsStatus.INITIAL;
        }
        if (mXAxisBoundsStatus == AxisBoundsStatus.INITIAL) {
            mCurrentViewport.left = mCompleteRange.left;
            mCurrentViewport.right = mCompleteRange.right;
        } else if (mXAxisBoundsManual && !mYAxisBoundsManual && mCompleteRange.width() != 0) {
            // get highest/lowest of current viewport
            // lowest
            double d = Double.MAX_VALUE;
            for (Series s : series) {
                Iterator<DataPointInterface> values = s.getValues(mCurrentViewport.left, mCurrentViewport.right);
                while (values.hasNext()) {
                    double v = values.next().getY();
                    if (d > v) {
                        d = v;
                    }
                }
            }

            mCurrentViewport.bottom = (float) d;

            // highest
            d = Double.MIN_VALUE;
            for (Series s : series) {
                Iterator<DataPointInterface> values = s.getValues(mCurrentViewport.left, mCurrentViewport.right);
                while (values.hasNext()) {
                    double v = values.next().getY();
                    if (d < v) {
                        d = v;
                    }
                }
            }
            mCurrentViewport.top = (float) d;
        }
    }

    public double getMinX(boolean completeRange) {
        if (completeRange) {
            return (double) mCompleteRange.left;
        } else {
            return (double) mCurrentViewport.left;
        }
    }

    public double getMaxX(boolean completeRange) {
        if (completeRange) {
            return (double) mCompleteRange.right;
        } else {
            return mCurrentViewport.right;
        }
    }

    public double getMinY(boolean completeRange) {
        if (completeRange) {
            return (double) mCompleteRange.bottom;
        } else {
            return mCurrentViewport.bottom;
        }
    }

    public double getMaxY(boolean completeRange) {
        if (completeRange) {
            return (double) mCompleteRange.top;
        } else {
            return mCurrentViewport.top;
        }
    }

    public void setMaxY(double y) {
        mCurrentViewport.top = (float) y;
    }

    public void setMinY(double y) {
        mCurrentViewport.bottom = (float) y;
    }

    public void setMaxX(double x) {
        mCurrentViewport.right = (float) x;
    }

    public void setMinX(double x) {
        mCurrentViewport.left = (float) x;
    }

    private void releaseEdgeEffects() {
        mEdgeEffectLeftActive
                = mEdgeEffectRightActive
                = false;
        mEdgeEffectLeft.onRelease();
        mEdgeEffectRight.onRelease();
    }

    private void fling(int velocityX, int velocityY) {
        Log.d("Viewport", "fling " + velocityX);
        velocityY = 0;
        releaseEdgeEffects();
        // Flings use math in pixels (as opposed to math based on the viewport).
        mScrollerStartViewport.set(mCurrentViewport);
        int maxX = (int)((mCurrentViewport.width()/mCompleteRange.width())*(float)mGraphView.getGraphContentWidth()) - mGraphView.getGraphContentWidth();
        int maxY = (int)((mCurrentViewport.height()/mCompleteRange.height())*(float)mGraphView.getGraphContentHeight()) - mGraphView.getGraphContentHeight();
        int startX = (int)((mCurrentViewport.left - mCompleteRange.left)/mCompleteRange.width())*maxX;
        int startY = (int)((mCurrentViewport.top - mCompleteRange.top)/mCompleteRange.height())*maxY;
        mScroller.forceFinished(true);
        mScroller.fling(
                startX,
                startY,
                velocityX,
                velocityY,
                0, maxX,
                0, maxY,
                mGraphView.getGraphContentWidth() / 2,
                mGraphView.getGraphContentHeight() / 2);
        ViewCompat.postInvalidateOnAnimation(mGraphView);
    }

    public void computeScroll() {
        if (true) return;

        boolean needsInvalidate = false;

        if (mScroller.computeScrollOffset()) {
            // The scroller isn't finished, meaning a fling or programmatic pan operation is
            // currently active.

            int completeWidth = (int)((mCompleteRange.width()/mCurrentViewport.width()) * (float) mGraphView.getGraphContentWidth());
            int completeHeight = (int)((mCompleteRange.height()/mCurrentViewport.height()) * (float) mGraphView.getGraphContentHeight());

            int currX = mScroller.getCurrX();
            int currY = mScroller.getCurrY();

            boolean canScrollX = mCurrentViewport.left > mCompleteRange.left
                    || mCurrentViewport.right < mCompleteRange.right;
            boolean canScrollY = mCurrentViewport.bottom > mCompleteRange.bottom
                    || mCurrentViewport.top < mCompleteRange.top;

            if (canScrollX
                    && currX < 0
                    && mEdgeEffectLeft.isFinished()
                    && !mEdgeEffectLeftActive) {
                mEdgeEffectLeft.onAbsorb((int) OverScrollerCompat.getCurrVelocity(mScroller));
                mEdgeEffectLeftActive = true;
                needsInvalidate = true;
            } else if (canScrollX
                    && currX > (completeWidth - mGraphView.getGraphContentWidth())
                    && mEdgeEffectRight.isFinished()
                    && !mEdgeEffectRightActive) {
                mEdgeEffectRight.onAbsorb((int) OverScrollerCompat.getCurrVelocity(mScroller));
                mEdgeEffectRightActive = true;
                needsInvalidate = true;
            }

            if (canScrollY
                    && currY < 0
                    && mEdgeEffectTop.isFinished()
                    && !mEdgeEffectTopActive) {
                mEdgeEffectTop.onAbsorb((int) OverScrollerCompat.getCurrVelocity(mScroller));
                mEdgeEffectTopActive = true;
                needsInvalidate = true;
            } else if (canScrollY
                    && currY > (completeHeight - mGraphView.getGraphContentHeight())
                    && mEdgeEffectBottom.isFinished()
                    && !mEdgeEffectBottomActive) {
                mEdgeEffectBottom.onAbsorb((int) OverScrollerCompat.getCurrVelocity(mScroller));
                mEdgeEffectBottomActive = true;
                needsInvalidate = true;
            }

            float currXRange = mCompleteRange.left + mCompleteRange.width()
                    * currX / completeWidth;
            float currYRange = mCompleteRange.top - mCompleteRange.height()
                    * currY / completeHeight;

            float currWidth = mCurrentViewport.width();
            float currHeight = mCurrentViewport.height();
            mCurrentViewport.left = currXRange;
            mCurrentViewport.right = currXRange + currWidth;
            //mCurrentViewport.bottom = currYRange;
            //mCurrentViewport.top = currYRange + currHeight;
        }

        if (needsInvalidate) {
            ViewCompat.postInvalidateOnAnimation(mGraphView);
        }
    }

    /**
     * Draws the overscroll "glow" at the four edges of the chart region, if necessary.
     *
     * @see EdgeEffectCompat
     */
    private void drawEdgeEffectsUnclipped(Canvas canvas) {
        // The methods below rotate and translate the canvas as needed before drawing the glow,
        // since EdgeEffectCompat always draws a top-glow at 0,0.

        boolean needsInvalidate = false;

        if (!mEdgeEffectTop.isFinished()) {
            final int restoreCount = canvas.save();
            canvas.translate(mGraphView.getGraphContentLeft(), mGraphView.getGraphContentTop());
            mEdgeEffectTop.setSize(mGraphView.getGraphContentWidth(), mGraphView.getGraphContentHeight());
            if (mEdgeEffectTop.draw(canvas)) {
                needsInvalidate = true;
            }
            canvas.restoreToCount(restoreCount);
        }

        //if (!mEdgeEffectBottom.isFinished()) {
        //    final int restoreCount = canvas.save();
        //    canvas.translate(2 * mContentRect.left - mContentRect.right, mContentRect.bottom);
        //    canvas.rotate(180, mContentRect.width(), 0);
        //    mEdgeEffectBottom.setSize(mContentRect.width(), mContentRect.height());
        //    if (mEdgeEffectBottom.draw(canvas)) {
        //        needsInvalidate = true;
        //    }
        //    canvas.restoreToCount(restoreCount);
        //}

        if (!mEdgeEffectLeft.isFinished()) {
            final int restoreCount = canvas.save();
            canvas.translate(mGraphView.getGraphContentLeft(), mGraphView.getGraphContentTop()+ mGraphView.getGraphContentHeight());
            canvas.rotate(-90, 0, 0);
            mEdgeEffectLeft.setSize(mGraphView.getGraphContentHeight(), mGraphView.getGraphContentWidth());
            if (mEdgeEffectLeft.draw(canvas)) {
                needsInvalidate = true;
            }
            canvas.restoreToCount(restoreCount);
        }

        if (!mEdgeEffectRight.isFinished()) {
            final int restoreCount = canvas.save();
            canvas.translate(mGraphView.getGraphContentLeft()+ mGraphView.getGraphContentWidth(), mGraphView.getGraphContentTop());
            canvas.rotate(90, 0, 0);
            mEdgeEffectRight.setSize(mGraphView.getGraphContentHeight(), mGraphView.getGraphContentWidth());
            if (mEdgeEffectRight.draw(canvas)) {
                needsInvalidate = true;
            }
            canvas.restoreToCount(restoreCount);
        }

        if (needsInvalidate) {
            ViewCompat.postInvalidateOnAnimation(mGraphView);
        }
    }

    public void drawFirst(Canvas c) {
        // draw background
        if (mBackgroundColor != Color.TRANSPARENT) {
            mPaint.setColor(mBackgroundColor);
            c.drawRect(
                    mGraphView.getGraphContentLeft(),
                    mGraphView.getGraphContentTop(),
                    mGraphView.getGraphContentLeft()+mGraphView.getGraphContentWidth(),
                    mGraphView.getGraphContentTop()+mGraphView.getGraphContentHeight(),
                    mPaint
            );
        }
    }

    public void draw(Canvas c) {
        drawEdgeEffectsUnclipped(c);
    }

    public int getBackgroundColor() {
        return mBackgroundColor;
    }

    public void setBackgroundColor(int mBackgroundColor) {
        this.mBackgroundColor = mBackgroundColor;
    }

    public boolean isScalable() {
        return mIsScalable;
    }

    public void setScalable(boolean mIsScalable) {
        this.mIsScalable = mIsScalable;
        if (mIsScalable) {
            mIsScrollable = true;
        }
    }

    public boolean isXAxisBoundsManual() {
        return mXAxisBoundsManual;
    }

    public void setXAxisBoundsManual(boolean mXAxisBoundsManual) {
        this.mXAxisBoundsManual = mXAxisBoundsManual;
        if (mXAxisBoundsManual) {
            mXAxisBoundsStatus = AxisBoundsStatus.FIX;
        }
    }

    public boolean isYAxisBoundsManual() {
        return mYAxisBoundsManual;
    }

    public void setYAxisBoundsManual(boolean mYAxisBoundsManual) {
        this.mYAxisBoundsManual = mYAxisBoundsManual;
        if (mYAxisBoundsManual) {
            mYAxisBoundsStatus = AxisBoundsStatus.FIX;
        }
    }

    public void scrollToEnd() {
        if (mXAxisBoundsManual) {
            float size = mCurrentViewport.width();
            mCurrentViewport.right = mCompleteRange.right;
            mCurrentViewport.left = mCompleteRange.right - size;
            mGraphView.onDataChanged(true, false);
        } else {
            Log.w("GraphView", "scrollToEnd works only with manual x axis bounds");
        }
    }
}
