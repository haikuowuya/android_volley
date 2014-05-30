/*
 * Copyright (C) 2011 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley.request;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.Response;
import com.android.volley.VolleyLog;
import com.android.volley.error.ParseError;
import com.android.volley.toolbox.HttpHeaderParser;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Bitmap.Config;
import android.graphics.PorterDuff.Mode;
import android.graphics.BitmapFactory;

/**
 * A canned request for getting an image at a given URL and calling
 * back with a decoded Bitmap.
 */
public class ImageRequest extends Request<Bitmap>
{
	/** Socket timeout in milliseconds for image requests */
	private static final int IMAGE_TIMEOUT_MS = 1000;

	/** Default number of retries for image requests */
	private static final int IMAGE_MAX_RETRIES = 2;

	/** Default backoff multiplier for image requests */
	private static final float IMAGE_BACKOFF_MULT = 2f;

	private final Response.Listener<Bitmap> mListener;
	private final Config mDecodeConfig;
	private final int mMaxWidth;
	private final int mMaxHeight;
	/** 是否是圆角 */
	private boolean mIsRoundCorner = false;
	/** 圆角大小 */
	private int mRoundCornerInPixel = 10;

	/** Decoding lock so that we don't decode more than one image at a time (to avoid OOM's) */
	private static final Object sDecodeLock = new Object();

	/**
	 * Creates a new image request, decoding to a maximum specified width and
	 * height. If both width and height are zero, the image will be decoded to
	 * its natural size. If one of the two is nonzero, that dimension will be
	 * clamped and the other one will be set to preserve the image's aspect
	 * ratio. If both width and height are nonzero, the image will be decoded to
	 * be fit in the rectangle of dimensions width x height while keeping its
	 * aspect ratio.
	 * 
	 * @param url
	 *            URL of the image
	 * @param listener
	 *            Listener to receive the decoded bitmap
	 * @param maxWidth
	 *            Maximum width to decode this bitmap to, or zero for none
	 * @param maxHeight
	 *            Maximum height to decode this bitmap to, or zero for none
	 * @param decodeConfig
	 *            Format to decode the bitmap to
	 * @param errorListener
	 *            Error listener, or null to ignore errors
	 */
	public ImageRequest(String url, Response.Listener<Bitmap> listener, int maxWidth, int maxHeight, Config decodeConfig, Response.ErrorListener errorListener)
	{
		super(Method.GET, url, errorListener);
		setRetryPolicy(new DefaultRetryPolicy(IMAGE_TIMEOUT_MS, IMAGE_MAX_RETRIES, IMAGE_BACKOFF_MULT));
		mListener = listener;
		mDecodeConfig = decodeConfig;
		mMaxWidth = maxWidth;
		mMaxHeight = maxHeight;
	}

	@Override
	public Priority getPriority()
	{
		return Priority.LOW;
	}

	/**
	 * Scales one side of a rectangle to fit aspect ratio.
	 * 
	 * @param maxPrimary
	 *            Maximum size of the primary dimension (i.e. width for
	 *            max width), or zero to maintain aspect ratio with secondary
	 *            dimension
	 * @param maxSecondary
	 *            Maximum size of the secondary dimension, or zero to
	 *            maintain aspect ratio with primary dimension
	 * @param actualPrimary
	 *            Actual size of the primary dimension
	 * @param actualSecondary
	 *            Actual size of the secondary dimension
	 */
	private static int getResizedDimension(int maxPrimary, int maxSecondary, int actualPrimary, int actualSecondary)
	{
		// If no dominant value at all, just return the actual.
		if (maxPrimary == 0 && maxSecondary == 0)
		{
			return actualPrimary;
		}

		// If primary is unspecified, scale primary to match secondary's scaling ratio.
		if (maxPrimary == 0)
		{
			double ratio = (double) maxSecondary / (double) actualSecondary;
			return (int) (actualPrimary * ratio);
		}

		if (maxSecondary == 0)
		{
			return maxPrimary;
		}

		double ratio = (double) actualSecondary / (double) actualPrimary;
		int resized = maxPrimary;
		if (resized * ratio > maxSecondary)
		{
			resized = (int) (maxSecondary / ratio);
		}
		return resized;
	}

	@Override
	public Response<Bitmap> parseNetworkResponse(NetworkResponse response)
	{
		// Serialize all decode on a global lock to reduce concurrent heap usage.
		synchronized (sDecodeLock)
		{
			try
			{
				return doParse(response);
			}
			catch (OutOfMemoryError e)
			{
				VolleyLog.e("Caught OOM for %d byte image, url=%s", response.data.length, getUrl());
				return Response.error(new ParseError(e));
			}
		}
	}

	/**
	 * The real guts of parseNetworkResponse. Broken out for readability.
	 */
	private Response<Bitmap> doParse(NetworkResponse response)
	{
		byte[] data = response.data;
		BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
		Bitmap bitmap = null;
		if (mMaxWidth == 0 && mMaxHeight == 0)
		{
			decodeOptions.inPreferredConfig = mDecodeConfig;
			bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
		}
		else
		{
			// If we have to resize this image, first get the natural bounds.
			decodeOptions.inJustDecodeBounds = true;
			BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
			int actualWidth = decodeOptions.outWidth;
			int actualHeight = decodeOptions.outHeight;

			// Then compute the dimensions we would ideally like to decode to.
			int desiredWidth = getResizedDimension(mMaxWidth, mMaxHeight, actualWidth, actualHeight);
			int desiredHeight = getResizedDimension(mMaxHeight, mMaxWidth, actualHeight, actualWidth);

			// Decode to the nearest power of two scaling factor.
			decodeOptions.inJustDecodeBounds = false;
			// TODO(ficus): Do we need this or is it okay since API 8 doesn't support it?
			// decodeOptions.inPreferQualityOverSpeed = PREFER_QUALITY_OVER_SPEED;
			decodeOptions.inSampleSize = findBestSampleSize(actualWidth, actualHeight, desiredWidth, desiredHeight);
			Bitmap tempBitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);

			// If necessary, scale down to the maximal acceptable size.
			if (tempBitmap != null && (tempBitmap.getWidth() > desiredWidth || tempBitmap.getHeight() > desiredHeight))
			{
				bitmap = Bitmap.createScaledBitmap(tempBitmap, desiredWidth, desiredHeight, true);
				tempBitmap.recycle();
			}
			else
			{
				bitmap = tempBitmap;
			}
		}

		if (bitmap == null)
		{
			return Response.error(new ParseError(response));
		}
		else
		{
			if (mIsRoundCorner)
			{
				bitmap = getRoundCornerImage(bitmap);
			}
			return Response.success(bitmap, HttpHeaderParser.parseCacheHeaders(response));
		}
	}

	@Override
	public void deliverResponse(Bitmap response)
	{
		mListener.onResponse(response);
	}

	/**
	 * Returns the largest power-of-two divisor for use in downscaling a bitmap
	 * that will not result in the scaling past the desired dimensions.
	 * 
	 * @param actualWidth
	 *            Actual width of the bitmap
	 * @param actualHeight
	 *            Actual height of the bitmap
	 * @param desiredWidth
	 *            Desired width of the bitmap
	 * @param desiredHeight
	 *            Desired height of the bitmap
	 */
	// Visible for testing.
	static int findBestSampleSize(int actualWidth, int actualHeight, int desiredWidth, int desiredHeight)
	{
		double wr = (double) actualWidth / desiredWidth;
		double hr = (double) actualHeight / desiredHeight;
		double ratio = Math.min(wr, hr);
		float n = 1.0f;
		while ((n * 2) <= ratio)
		{
			n *= 2;
		}

		return (int) n;
	}

	public boolean isRoundCorner()
	{
		return mIsRoundCorner;
	}

	public ImageRequest setIsRoundCorner(boolean isRoundCorner)
	{
		this.mIsRoundCorner = isRoundCorner;
		return this;
	}

	public int getRoundCornerInPixel()
	{
		return mRoundCornerInPixel;
	}

	public ImageRequest setRoundCornerInPixel(int roundCornerInPixel)
	{
		this.mRoundCornerInPixel = roundCornerInPixel;
		return this;
	}

	/** 获得圆角图片 */
	public Bitmap getRoundCornerImage(Bitmap bitmap)
	{
		// 创建一个和原始图片一样大小位图
		Bitmap roundConcerImage = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Config.ARGB_8888);
		// 创建带有位图roundConcerImage的画布
		Canvas canvas = new Canvas(roundConcerImage);
		// 创建画笔
		Paint paint = new Paint();
		// 创建一个和原始图片一样大小的矩形
		Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
		RectF rectF = new RectF(rect);
		// 去锯齿
		paint.setAntiAlias(true);
		// 画一个和原始图片一样大小的圆角矩形
		canvas.drawRoundRect(rectF, mRoundCornerInPixel, mRoundCornerInPixel, paint);
		// 设置相交模式
		paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
		// 把图片画到矩形去
		canvas.drawBitmap(bitmap, null, rect, paint);
		return roundConcerImage;
	}

}
