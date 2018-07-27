package ar.rulosoft.mimanganu.componentes.readers.continuos;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import ar.rulosoft.mimanganu.componentes.readers.Reader;
import rapid.decoder.BitmapDecoder;

/**
 * Created by Raul on 22/10/2015.
 */

public abstract class ReaderContinuous extends Reader implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener {
    protected int currentPage = 0, lastBestVisible = 0;
    protected float lastPageBestPercent = 0f;
    protected int mTextureMax = 200;

    protected boolean animatingSeek = false;
    protected boolean stopAnimationsOnTouch = false, stopAnimationOnVerticalOver = false, stopAnimationOnHorizontalOver = false;
    protected boolean iniVisibility, endVisibility;
    protected boolean pagesLoaded = false, viewReady = false, layoutReady = false;
    protected float xScroll = 0, yScroll = 0;
    protected ScaleGestureDetector mScaleDetector;
    protected GestureDetector mGestureDetector;
    protected Rect screen;
    Canvas cache;
    float mScaleFactor = 1.f;
    Matrix m = new Matrix();
    Paint mPaint = new Paint();
    int screenHeight, screenWidth;
    int screenHeightSS, screenWidthSS; // Sub scaled
    Handler mHandler;
    protected ArrayList<Page> pages;
    protected ArrayList<Page.Line.Segment>[] segments;
    ArrayList<Page.Line.Segment> toDraw = new ArrayList<>();
    boolean drawing = false, preparing = false;

    float ppi;


    public ReaderContinuous(Context context) {
        super(context);
        init(context);
    }

    protected abstract void absoluteScroll(float x, float y);

    protected abstract void relativeScroll(double distanceX, double distanceY);

    protected abstract void calculateParticularScale();

    protected abstract void calculateParticularScale(Page page);

    protected abstract void calculateVisibilities();

    protected abstract void generateSegmentsArray();

    public abstract void goToPage(int aPage);

    protected abstract Page getNewPage(int number);

    public abstract void reset();

    public abstract void seekPage(int index);

    public abstract void postLayout();

    public abstract void reloadImage(int idx);

    public abstract float getPagePosition(int page);// Starting from 0

    @Override
    public void setBlueFilter(float bf) {
        ColorMatrix cm = new ColorMatrix();
        cm.set(new float[]{1, 0, 0, 0, 0,
                0, (0.6f + 0.4f * bf), 0, 0, 0,
                0f, 0f, (0.1f + 0.9f * bf), 0, 0,
                0, 0, 0, 1f, 0});
        mPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        this.postInvalidate();
    }

    @Override
    public int getPages() {
        return pages.size();
    }

    @Override
    protected int transformPage(int page) {
        return page + 1;
    }

    public void freeMemory() {
        if (pages != null)
            for (Page p : pages) {
                p.freeMemory();
            }
    }

    public void freePage(int idx) {
        if (isValidIdx(idx))
            getPage(idx).freeMemory();
    }

    @Override
    public int getCurrentPage() {
        return currentPage;
    }

    public String getPath(int idx) {
        int iIdx = idx - 1;
        Page p = getPage(iIdx);
        if (p != null) {
            return p.getPath();
        } else {
            return "";
        }
    }

    public Page getPage(int page) {
        if (isValidIdx(page))
            return pages.get(page);
        return null;
    }

    private void init(Context context) {
        mPaint.setFilterBitmap(true);
        setWillNotDraw(false);
        mScaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        mGestureDetector = new GestureDetector(getContext(), this);
        mHandler = new Handler();
        ppi = context.getResources().getDisplayMetrics().density * 160.0f;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        screenHeight = Math.abs(bottom - top);
        screenWidth = Math.abs(right - left);
        screenWidthSS = screenWidth;
        screenHeightSS = screenHeight;
        if (pages != null) {
            calculateParticularScale();
            calculateVisibilities();
            layoutReady = true;
            generateDrawPool();
        }
        postLayout();
        super.onLayout(changed, left, top, right, bottom);
    }

    protected void generateDrawPool() {
        if (!preparing) {
            preparing = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ArrayList<Page.Line.Segment> _segments = new ArrayList<>();
                    if (viewReady) {
                        lastBestVisible = -1;
                        iniVisibility = false;
                        endVisibility = false;
                        lastPageBestPercent = 0f;
                        if (pages != null) {
                            boolean tested = false;
                            while (!tested) {
                                tested = true;
                                try {
                                    for (Page page : pages.subList(getIndex(pages, xScroll), getIndex(pages, xScroll + screenWidth) + 1)) {
                                        if (page.isVisible()) {
                                            _segments.addAll(page.getVisibleSegments());
                                            if (page.getVisiblePercent() >= lastPageBestPercent) {
                                                page.isVisible();
                                                lastPageBestPercent = page.getVisiblePercent();
                                                lastBestVisible = pages.indexOf(page);
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    tested = false;
                                }//catch errors caused for array concurrent modify
                            }
                            if (currentPage != transformPage(lastBestVisible)) {
                                currentPage = transformPage(lastBestVisible);
                                readerListener.onPageChanged(currentPage);
                            }
                        }
                    } else if (pagesLoaded) {
                        //TODO if (mViewReadyListener != null)
                        viewReady = true;
                        preparing = false;
                        absoluteScroll(xScroll, yScroll);
                    }
                    toDraw = _segments;
                    drawing = true;
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            invalidate();
                        }
                    });
                }
            }).start();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (toDraw.size() > 0) {
            for (Page.Line.Segment s : toDraw) {
                s.draw(canvas);
            }
            cache = canvas;
        } else {
            generateDrawPool();
            if (cache != null)
                canvas = cache;
        }
        preparing = false;
        drawing = false;
    }

    public void setPaths(List<String> paths) {
        pages = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            pages.add(initValues(paths.get(i), i + 1));
        }
        if (layoutReady) {
            calculateParticularScale();
            calculateVisibilities();
            generateDrawPool();
        }
    }

    protected Page initValues(String path, int number) {
        Page dimension = getNewPage(number);
        dimension.path = path;
        File f = new File(path);
        if (f.exists()) {
            try {
                dimension.path = path;
                InputStream inputStream = new FileInputStream(path);
                BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
                bitmapOptions.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(inputStream, null, bitmapOptions);
                dimension.original_width = bitmapOptions.outWidth;
                dimension.original_height = bitmapOptions.outHeight;
                inputStream.close();
            } catch (IOException ignored) {
            }
            dimension.initValues();
        } else {
            try {
                dimension.error = true;
                InputStream inputStream = getBitmapFromAsset("broke.png");
                BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
                bitmapOptions.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(inputStream, null, bitmapOptions);
                dimension.original_width = bitmapOptions.outWidth;
                dimension.original_height = bitmapOptions.outHeight;
                inputStream.close();
            } catch (IOException ignored) {
            }
            dimension.initValues();
        }
        return dimension;
    }

    protected void setPage(int page) {
        if (readerListener != null)
            readerListener.onPageChanged(page);
        currentPage = page;
        generateDrawPool();
    }

    public boolean isLastPageVisible() {
        return pages != null && !pages.isEmpty() && pages.get(pages.size() - 1).isVisible();
    }

    public void setScrollSensitive(float mScrollSensitive) {
        this.mScrollSensitive = mScrollSensitive;
    }

    public void setMaxTexture(int mTextureMax) {
        //if (mTextureMax > 0)
        //TODO this.mTextureMax = mTextureMax;
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle b = new Bundle();
        b.putParcelable("state", super.onSaveInstanceState());
        return b;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            super.onRestoreInstanceState(((Bundle) state).getParcelable("state"));
        } else {
            super.onRestoreInstanceState(state);
        }
    }


    private InputStream getBitmapFromAsset(String strName) {
        AssetManager assetManager = getContext().getAssets();
        InputStream iStr = null;
        try {
            iStr = assetManager.open(strName);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return iStr;
    }


    public enum ImagesStates {NULL, RECYCLED, ERROR, LOADING, LOADED}

    public abstract class Page {
        int number;
        String path;
        float original_width;
        float original_height;
        float init_visibility;       // on horizontal this is on x axis, vertical in y
        float end_visibility;        // same here
        float unification_scale;     // not all pages are of same size, height for horizontal, width for vertical
        float scaled_height;         // post unification scale value
        float scaled_width;
        boolean initialized = false;
        Line[] lines;
        int pxs, pys;               //part part size o x or y
        int xp, yp, tp;             //x and y parts count and total
        boolean error = false;
        boolean lastVisibleState = false;

        public Page(int number) {
            this.number = number;
        }

        public abstract boolean isVisible();

        abstract Line getNewLine(float init_visibility, float end_visibility, int segments);

        public abstract float getVisiblePercent();

        // base horizontal reader
        public void initValues() {
            yp = (int) (original_height / mTextureMax) + 1;
            pys = (int) (original_height / yp);
            xp = (int) (original_width / mTextureMax) + 1;
            pxs = (int) (original_width / xp);
            tp = xp * yp;
            lines = new Line[xp];
            Line.Segment[] lineSegments = new Line.Segment[yp];
            for (int i = 0; i < xp; i++) {
                lines[i] = getNewLine(init_visibility + lineSegments[0].dx,
                    init_visibility + lineSegments[0].dx + pxs, yp);
                for (int j = 0; j < yp; j++) {
                    lines[i].segments[j].dy = i * yp;
                    lines[i].segments[j].dx = j * xp;
                }
            }
            initialized = true;
        }

        public String getPath() {
            return path;
        }

        public void freeMemory() {
            if (segments != null)
                for (int i = 0; i < xp; i++) {
                    lines[i].freeMemory();
                }
        }

        public ArrayList<Line.Segment> getVisibleSegments() {
            ArrayList<Line.Segment> _segments = new ArrayList<>();
            if (segments != null)
                for (int i = 0; i < xp; i++) {
                    if (lines[i].isVisible()) {
                        _segments.add(lines[i].segments[0]);
                    }
                }
            return _segments;
        }

        public void showOnLoad(final Line line) {
            if (line.isVisible()) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        ValueAnimator va = ValueAnimator.ofInt(0, 255);
                        va.setDuration(300);
                        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                            @Override
                            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                                line.alpha = (int) valueAnimator.getAnimatedValue();
                                generateDrawPool();
                            }
                        });
                        va.addListener(new Animator.AnimatorListener() {
                            @Override
                            public void onAnimationStart(Animator animator) {

                            }

                            @Override
                            public void onAnimationEnd(Animator animator) {
                                line.alpha = 255;
                                generateDrawPool();
                            }

                            @Override
                            public void onAnimationCancel(Animator animator) {
                                line.alpha = 255;
                                generateDrawPool();
                            }

                            @Override
                            public void onAnimationRepeat(Animator animator) {

                            }
                        });
                        va.start();

                    }
                });
            } else {
                line.alpha = 255;
                generateDrawPool();
            }
        }

        public abstract class Line {
            float ini_line_visibility;
            float end_line_visibility;
            boolean visible = false;
            int alpha;
            int segmentsLoad = 0;
            Segment[] segments;

            public abstract Segment getNewSegment();

            public Line(float ini_line_visibility, float end_line_visibility, int segments) {
                this.ini_line_visibility = ini_line_visibility;
                this.end_line_visibility = end_line_visibility;
                this.segments = new Segment[segments];
            }

            public boolean isVisible() {
                return visible;
            }

            public void freeMemory() {
                visible = false;
                segmentsLoad = 0;
                for (int j = 0; j < segments.length; j++) {
                    segments[j].freeMemory();
                }
            }

            public void loadBitmaps() {
                for (int j = 0; j < segments.length; j++) {
                    segments[j].loadBitmap();
                }
            }

            public void draw(Canvas canvas) {
                for (int j = 0; j < segments.length; j++) {
                    segments[j].draw(canvas);
                }
            }

            public void visibilityChanged() {
                if (!animatingSeek) {
                    visible = !visible;
                    if (visible) {
                        loadBitmaps();
                    } else {
                        freeMemory();
                    }
                }
            }


            public boolean segmentLoaded(){
                segmentsLoad++;
                return  segmentsLoad == segments.length;
            }

            public abstract class Segment {
                Bitmap segment;
                ImagesStates state;
                int dx, dy;

                public Segment() {
                    alpha = 255;
                    state = ImagesStates.NULL;
                }

                public abstract void draw(Canvas canvas);

                public void freeMemory() {
                    if (segment != null) {
                        state = ImagesStates.RECYCLED;
                        segment.recycle();
                        segment = null;
                        alpha = 0;
                    }
                    state = ImagesStates.NULL;
                }

                public void loadBitmap() {
                    if (!animatingSeek)
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (state == ImagesStates.NULL) {
                                        state = ImagesStates.LOADING;
                                        alpha = 0;
                                        BitmapFactory.Options options = new BitmapFactory.Options();
                                        options.inPreferredConfig = Bitmap.Config.RGB_565;
                                        if (!error) {
                                            if (tp == 1) {
                                                segment = BitmapDecoder.from(path).useBuiltInDecoder(false).config(Bitmap.Config.RGB_565).decode();
                                                if (segments == null) {
                                                    segment = BitmapDecoder.from(path).useBuiltInDecoder(true).config(Bitmap.Config.RGB_565).decode();
                                                }
                                            } else {
                                                try {
                                                    int right = dx + pxs + 2, bottom = dy + pys + 2;
                                                    if (right > original_width)
                                                        right = (int) original_width;
                                                    if (bottom > original_height)
                                                        bottom = (int) original_height;
                                                    segment = BitmapDecoder.from(path).region(dx, dy, right, bottom).useBuiltInDecoder(false).config(Bitmap.Config.RGB_565).decode();
                                                    if (segment == null) {
                                                        segment = BitmapDecoder.from(path).region(dx, dy, right, bottom).useBuiltInDecoder(true).config(Bitmap.Config.RGB_565).decode();
                                                    }
                                                } catch (Exception e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                        } else {
                                            InputStream inputStream = getBitmapFromAsset("broke.png");
                                            if (tp == 1) {
                                                segment = BitmapFactory.decodeStream(inputStream, null, options);
                                            } else {
                                                try {
                                                    int right = dx + pxs + 2, bottom = dy + pys + 2;
                                                    if (right > original_width)
                                                        right = (int) original_width;
                                                    if (bottom > original_height)
                                                        bottom = (int) original_height;
                                                    segment = BitmapDecoder.from(inputStream).region(dx, dy, right, bottom).useBuiltInDecoder(false).config(Bitmap.Config.RGB_565).decode();
                                                } catch (Exception e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                            inputStream.close();
                                        }
                                        if (segment != null) {
                                            state = ImagesStates.LOADED;
                                            if(segmentLoaded())
                                                showOnLoad(Line.this);
                                        } else {
                                            state = ImagesStates.NULL;
                                        }
                                    }
                                } catch (Exception | OutOfMemoryError e) {
                                    e.printStackTrace();
                                }
                            }
                        }).start();
                }
            }

        }
    }


    public static int getIndex(ArrayList<Page> a, float sv) {
        int b = 0;
        if (a.size() == 0) {
            return b;
        }
        int low = 0;
        int high = a.size() - 1;

        while (low <= high) {
            int middle = (low + high) / 2;
            if (sv > a.get(middle).end_visibility) {
                low = middle + 1;
            } else if (sv < a.get(middle).init_visibility) {
                high = middle - 1;
            } else { // The element has been found
                return middle;
            }
        }
        return -1;
    }

    /**
     * Gesture section
     */

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent ev) {
        stopAnimationsOnTouch = true;
        mScaleDetector.onTouchEvent(ev);
        if (!mScaleDetector.isInProgress())
            mGestureDetector.onTouchEvent(ev);
        generateDrawPool();
        return true;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        distanceX = (distanceX * mScrollSensitive / mScaleFactor);
        distanceY = (distanceY * mScrollSensitive / mScaleFactor);
        relativeScroll(distanceX, distanceY);
        generateDrawPool();
        return true;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, final float velocityX, final float velocityY) {
        stopAnimationsOnTouch = false;
        stopAnimationOnHorizontalOver = false;
        stopAnimationOnVerticalOver = false;
        mHandler.post(new Runnable() {
            final int fps = 50;
            final float deceleration_rate = 0.90f;
            final int timeLapse = 1000 / fps;
            final float min_velocity = 500;
            float velocity_Y = velocityY * mScrollSensitive;
            float velocity_X = velocityX * mScrollSensitive;

            @Override
            public void run() {
                relativeScroll(-velocity_X / fps, -(velocity_Y / fps));
                velocity_Y = velocity_Y * deceleration_rate;
                velocity_X = velocity_X * deceleration_rate;
                if (stopAnimationOnHorizontalOver) {
                    velocity_X = 0;
                }
                if (stopAnimationOnVerticalOver) {
                    velocity_Y = 0;
                }
                if ((Math.abs(velocity_Y) > min_velocity || Math.abs(velocity_X) > min_velocity) && !stopAnimationsOnTouch) {
                    mHandler.postDelayed(this, timeLapse);
                }
                generateDrawPool();
            }
        });
        return false;
    }

    @Override
    public void onShowPress(MotionEvent e) {
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onDoubleTap(final MotionEvent e) {
        final float ini = mScaleFactor, end;
        if (mScaleFactor < 1.8) {
            end = 2f;
        } else if (mScaleFactor < 2.8) {
            end = 3f;
        } else {
            end = 1f;
        }

        ValueAnimator va = ValueAnimator.ofFloat(ini, end);
        va.setDuration(300);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            float nScale;
            float final_x = ((xScroll + e.getX() / ini)) - (screenWidth / 2) + (screenWidth * end - screenWidth) / (end * 2) - xScroll;
            float final_y = ((yScroll + e.getY() / ini)) - (screenHeight / 2) + (screenHeight * end - screenHeight) / (end * 2) - yScroll;
            float initial_x_scroll = xScroll;
            float initial_y_scroll = yScroll;
            float nPx, nPy, aP;

            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                nScale = (float) valueAnimator.getAnimatedValue();
                aP = valueAnimator.getAnimatedFraction();
                nPx = initial_x_scroll + (final_x * aP);
                nPy = initial_y_scroll + (final_y * aP);
                mScaleFactor = nScale;
                absoluteScroll(nPx, nPy);
                generateDrawPool();
            }
        });
        va.start();
        return false;
    }

    protected class ScaleListener extends
            ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float nScale = Math.max(.8f, Math.min(mScaleFactor * detector.getScaleFactor(), 3.0f));
            if ((nScale <= 3f && nScale >= 1f)) {//can be better, but how ?
                float final_x = (((((screenWidth * nScale) - screenWidth)) / nScale) - ((((screenWidth * mScaleFactor) - screenWidth)) / mScaleFactor)) * detector.getFocusX() / screenWidth;
                float final_y = (((((screenHeight * nScale) - screenHeight)) / nScale) - ((((screenHeight * mScaleFactor) - screenHeight)) / mScaleFactor)) * detector.getFocusY() / screenHeight;
                screenHeightSS = screenHeight;
                screenWidthSS = screenWidth;
                relativeScroll(final_x, final_y);
            } else if (nScale < 1) {
                screenHeightSS = (int) (nScale * screenHeight);
                screenWidthSS = (int) (nScale * screenWidth);
                relativeScroll(0, 0);
            }
            mScaleFactor = nScale;
            generateDrawPool();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            super.onScaleEnd(detector);
        }
    }
}
