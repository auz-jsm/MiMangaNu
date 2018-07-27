package ar.rulosoft.mimanganu.componentes.readers.continuos;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.support.annotation.NonNull;
import android.view.MotionEvent;

import java.util.ArrayList;

import static ar.rulosoft.mimanganu.componentes.readers.continuos.ReaderContinuous.Page.Line.*;

/**
 * Created by Raul on 21/06/2016.
 */
public abstract class HorizontalReader extends ReaderContinuous {

    protected float totalWidth = 0;

    public HorizontalReader(Context context) {
        super(context);
    }

    @Override
    protected void calculateParticularScale() {
        for (Page dimension : pages) {
            if (!dimension.error) {
                dimension.unification_scale = (screenHeight / dimension.original_height);
                dimension.scaled_width = dimension.original_width * dimension.unification_scale;
                dimension.scaled_height = screenHeight;
            } else {
                dimension.unification_scale = (screenWidth / dimension.original_width);
                dimension.scaled_width = screenWidth;
                dimension.scaled_height = screenHeight * dimension.unification_scale;
            }
        }
    }

    @Override
    protected void calculateParticularScale(Page dimension) {
        if (!dimension.error) {
            dimension.unification_scale = (screenHeight / dimension.original_height);
            dimension.scaled_width = dimension.original_width * dimension.unification_scale;
            dimension.scaled_height = screenHeight;
        } else {
            dimension.original_width = screenWidth;
            dimension.original_height = screenHeight;
            dimension.unification_scale = 1;
            dimension.scaled_width = screenWidth;
            dimension.scaled_height = screenHeight;
        }
    }

    @Override
    protected void generateSegmentsArray() {
        int hSegmentCount = 0;
        for (Page page : pages) {
            hSegmentCount += page.xp;
        }
        segments = new ArrayList[hSegmentCount];
        for (Page page : pages) {
            segments[page.number - 1] = new ArrayList<>();
            for (int i = 0; i < page.yp; i++) {
                for (int j = 0; j < page.xp; j++) {

                }
            }
        }
    }

    @Override
    public void postLayout() {
        absoluteScroll(getPagePosition(currentPage - 1), yScroll);
        generateDrawPool();
        if (readerListener != null) {
            readerListener.onPageChanged(currentPage - 1);
        }
    }

    @Override
    public void seekPage(int index) {
        int page = index - 1;
        if (viewReady && pagesLoaded) {
            absoluteScroll(getPagePosition(page), yScroll);
            generateDrawPool();
        }
        currentPage = index;
        if (readerListener != null) {
            readerListener.onPageChanged(currentPage);
        }
    }

    public void reloadImage(int idx) {
        int pageIdx = idx - 1;
        if (pages != null && pageIdx < pages.size() && pageIdx >= 0) {
            int cIdx = currentPage - 1;
            if (pages.size() < cIdx || cIdx < 0)
                cIdx = 0;
            float value = 0;
            if (pages.get(cIdx) != null)
                value = pages.get(cIdx).init_visibility;
            Page page = initValues(pages.get(pageIdx).path, pageIdx + 1);
            pages.set(pageIdx, page);
            calculateParticularScale(pages.get(pageIdx));
            calculateVisibilities();
            if (pages.get(cIdx) != null)
                value = value - pages.get(cIdx).init_visibility;
            relativeScroll(-value, 0);
            generateDrawPool();
        }
    }

    @Override
    public void goToPage(final int aPage) {
        if (pages != null) {
            final float finalScroll = getPagePosition(aPage - 1);
            final ValueAnimator va = ValueAnimator.ofFloat(xScroll, finalScroll).setDuration(500);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator valueAnimator) {
                    relativeScroll((float) valueAnimator.getAnimatedValue() - xScroll, 0);
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            generateDrawPool();
                        }
                    });
                }
            });
            va.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    if (Math.abs(aPage - currentPage - 1) > 1)
                        animatingSeek = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    animatingSeek = false;
                    currentPage = aPage;
                    generateDrawPool();
                }

                @Override
                public void onAnimationCancel(Animator animation) {

                }

                @Override
                public void onAnimationRepeat(Animator animation) {

                }
            });
            va.start();
        }
    }

    @Override
    protected Page getNewPage(int number) {
        return new HPage(number);
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }


    @Override
    public void reset() {
        xScroll = 0;
        yScroll = 0;
        currentPage = 1;
        pages = null;
        pagesLoaded = false;
        viewReady = false;
        animatingSeek = false;
        totalWidth = 0;
    }


    protected class HPage extends Page {

        public HPage(int number) {
            super(number);
        }

        @Override
        public boolean isVisible() {

            float visibleRight = (xScroll * mScaleFactor + screenWidth);
            float visibleLeft = (xScroll * mScaleFactor);

            final boolean visibility = (visibleRight >= init_visibility * mScaleFactor && end_visibility * mScaleFactor >= visibleLeft);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (visibility != lastVisibleState) {
                        lastVisibleState = visibility;
                        if (!visibility) {
                            freeMemory();
                        }
                    }
                    if (visibility && segments != null)
                        for (int i = 0; i < yp; i++) {
                            for (int j = 0; j < yp; j++) {
                                // TODO segments[i][j].checkVisibility();
                            }
                        }
                }
            }).start();
            return visibility;
        }

        @Override
        Line getNewLine(float init_visibility, float end_visibility, int segments) {
            return new HLine(init_visibility, end_visibility, segments);
        }

        @Override
        public float getVisiblePercent() {
            if (init_visibility < xScroll) {
                if (end_visibility < xScroll + screenWidth) {
                    return (end_visibility - xScroll) / scaled_width;
                } else {
                    return screenWidth / scaled_width;
                }
            } else {
                if (end_visibility < xScroll + screenWidth) {
                    return 1;
                } else {
                    return (xScroll + screenWidth - init_visibility) / scaled_width;
                }
            }
        }

        public class HLine extends Line {

            public HLine(float ini_line_visibility, float end_line_visibility, int segments) {
                super(ini_line_visibility, end_line_visibility, segments);
            }

            @Override
            public Segment getNewSegment() {
                return new HSegment();
            }

            public class HSegment extends Segment {

                @Override
                public void draw(Canvas canvas) {
                    if (state == ImagesStates.LOADED) {
                        m.reset();
                        mPaint.setAlpha(alpha);
                        m.postTranslate(dx, dy);
                        m.postScale(unification_scale, unification_scale);
                        m.postTranslate(init_visibility - xScroll, -yScroll);
                        m.postScale(mScaleFactor, mScaleFactor);
                        try {
                            canvas.drawBitmap(segment, m, mPaint);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
    }
}
