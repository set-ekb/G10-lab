package com.g10blelab.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class TrackView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<TripTracker.TripPoint> points = new ArrayList<>();

    public TrackView(Context context) {
        super(context);

        gridPaint.setColor(0xFF30343A);
        gridPaint.setStrokeWidth(1f);

        linePaint.setColor(0xFF42A5F5);
        linePaint.setStrokeWidth(6f);
        linePaint.setStyle(Paint.Style.STROKE);

        pointPaint.setColor(0xFFFFC107);
        pointPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(0xFFB0BEC5);
        textPaint.setTextSize(28f);

        setBackgroundColor(0xFF15181C);
    }

    public void setPoints(List<TripTracker.TripPoint> points) {
        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();

        for (int i = 1; i < 5; i++) {
            float x = w * i / 5f;
            float y = h * i / 5f;
            canvas.drawLine(x, 0, x, h, gridPaint);
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        if (points.size() < 2) {
            canvas.drawText("GPS-трек появится здесь", 24, h / 2f, textPaint);
            return;
        }

        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;

        for (TripTracker.TripPoint p : points) {
            minLat = Math.min(minLat, p.latitude);
            maxLat = Math.max(maxLat, p.latitude);
            minLon = Math.min(minLon, p.longitude);
            maxLon = Math.max(maxLon, p.longitude);
        }

        double latSpan = Math.max(0.000001, maxLat - minLat);
        double lonSpan = Math.max(0.000001, maxLon - minLon);

        float pad = 35f;
        float drawW = Math.max(1, w - pad * 2);
        float drawH = Math.max(1, h - pad * 2);

        float prevX = 0;
        float prevY = 0;
        boolean first = true;

        for (TripTracker.TripPoint p : points) {
            float x = pad + (float) ((p.longitude - minLon) / lonSpan) * drawW;
            float y = pad + (1f - (float) ((p.latitude - minLat) / latSpan)) * drawH;

            if (!first) {
                canvas.drawLine(prevX, prevY, x, y, linePaint);
            } else {
                canvas.drawCircle(x, y, 10f, pointPaint);
                first = false;
            }

            prevX = x;
            prevY = y;
        }

        canvas.drawCircle(prevX, prevY, 12f, pointPaint);
        canvas.drawText(points.size() + " GPS points", 20, 34, textPaint);
    }
}
