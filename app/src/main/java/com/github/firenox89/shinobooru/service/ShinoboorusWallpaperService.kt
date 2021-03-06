package com.github.firenox89.shinobooru.service

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.service.wallpaper.WallpaperService
import android.util.Size
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.github.firenox89.shinobooru.model.DownloadedPost
import com.github.firenox89.shinobooru.model.FileManager
import com.github.firenox89.shinobooru.model.Post
import com.github.salomonbrys.kodein.KodeinInjected
import com.github.salomonbrys.kodein.KodeinInjector
import com.github.salomonbrys.kodein.android.appKodein
import com.github.salomonbrys.kodein.instance
import rx.lang.kotlin.PublishSubject
import rx.schedulers.Schedulers
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Creates a live wallpaper that uses the downloaded posts as homescreen backgrounds.
 * The wallpaper changes on every time the homescreen gets visible(like when minimizing an app or turn on the screen).
 * Also landscape and portray mode get a different set of wallpapers.
 */
class ShinoboorusWallpaperService : WallpaperService() {

    val TAG = "WallpaperService"

    override fun onCreateEngine(): Engine {
        return ShinoboorusWallpaperEngine()
    }

    companion object {
        val black = Paint().apply {
            color = 0x000000
            alpha = 255
        }
        //smooths the scaled image while drawing
        val filter = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }
    }

    /**
     * The engine drawing on te live wallpaper.
     */
    inner class ShinoboorusWallpaperEngine : Engine(), KodeinInjected {
        override val injector = KodeinInjector()
        val pref: SharedPreferences by instance()
        //TODO: make the wallpaperService more configurable

        private var displayWidth: Int = 0
        private var displayHeight: Int = 0

        private val clickEventStream = PublishSubject<MotionEvent>()
        private val drawRequestQueue = PublishSubject<() -> Unit>()
        private val drawScheduler = Schedulers.from(Executors.newSingleThreadExecutor())

        /**
         * Sets up the event handler for double clicks und the event queue for drawing request.
         */
        init {
            inject(appKodein())

            clickEventStream.buffer(clickEventStream.debounce(300, TimeUnit.MILLISECONDS)).forEach {
                if (it.size == 2) draw()
            }

            drawRequestQueue.throttleFirst(500, TimeUnit.MILLISECONDS, drawScheduler).subscribe { it.invoke() }
        }

        /**
         * Issue a draw call on a visibility change.
         *
         * @param visible gets ignored.
         */
        override fun onVisibilityChanged(visible: Boolean) {
            //hide drawing through doing it when invisible
            if (!visible) draw()
        }

        /**
         * Sends the ACTION_UP events the [clickEventStream].
         * Calls super method.
         *
         * @param event filter and put on [clickEventStream]
         */
        override fun onTouchEvent(event: MotionEvent?) {
            if (event?.action == MotionEvent.ACTION_UP) {
                clickEventStream.onNext(event)
            }
            super.onTouchEvent(event)
        }

        /**
         * Gets called on display rotation, sets new display width and height and issues a draw request.
         * Calls super method.
         *
         * @param holder gets ignored
         * @param format gets ignored
         * @param width will be set as new [displayWidth]
         * @param height will be set as new [displayHeight]
         */
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            displayWidth = width
            displayHeight = height
            draw()
            super.onSurfaceChanged(holder, format, width, height)
        }

        /**
         * Draws an image fitting the current rotation and draws it to the canvas obtained from the surface.
         */
        private fun draw() {
            drawRequestQueue.onNext {
                try {
                    //stop if surface got invalid
                    if (isSurfaceInvalid()) return@onNext
                    val canvas = surfaceHolder.lockCanvas()
                    //clear previous drawing
                    canvas.drawPaint(black)
                    val image = pickImage()
                    val transformationInfo = calcTransformation(image)
                    if (isSurfaceInvalid()) return@onNext
                    //scales image
                    canvas.translate(transformationInfo.second.width.toFloat() / 2,
                            transformationInfo.second.height.toFloat() / 2)
                    if (isSurfaceInvalid()) return@onNext
                    //draws scaled image
                    canvas.drawBitmap(image, transformationInfo.first, filter)
                    if (isSurfaceInvalid()) return@onNext
                    //TODO this sometimes crashes when setting this as lockscreen
                    surfaceHolder.unlockCanvasAndPost(canvas)
                    image.recycle()
                } catch (OoM: OutOfMemoryError) {
                    OoM.printStackTrace()
                }
            }
        }

        /**
         * Returns true if surface is invalid
         */
        private fun isSurfaceInvalid(): Boolean {
            return !surfaceHolder.surface.isValid
        }

        /**
         * Calculates the scaling factors, apply the scaling to a transformation matrix,
         * calculates the drawing offsets and retrun them as a pair.
         *
         * @param image to calculate the transformation and offset for
         *
         * @return a [Pair] containing the matrix and offset
         */
        private fun calcTransformation(image: Bitmap): Pair<Matrix, Size> {
            val width = image.width
            val height = image.height
            val ratioBitmap = width.toFloat() / height.toFloat()
            val ratioDisplay = displayWidth.toFloat() / displayHeight.toFloat()

            var finalWidth = displayWidth
            var finalHeight = displayHeight

            //in landscape mode takes full display width and calculate height according to the image ration,
            //in portray mode its the other way around, since images are picked according to the screen rotation
            //we don't have to border about the case where image and display ratios are not both above or below 1
            if (ratioDisplay > 1) {
                //landscape mode
                finalWidth = (displayHeight.toFloat() * ratioBitmap).toInt()
            } else {
                //portray mode
                finalHeight = (displayWidth.toFloat() / ratioBitmap).toInt()
            }

            if (finalWidth > displayWidth) {
                finalHeight = (displayWidth.toFloat() / ratioBitmap).toInt()
                finalWidth = displayWidth
            }

            if (finalHeight > displayHeight) {
                finalWidth = (displayHeight.toFloat() * ratioBitmap).toInt()
                finalHeight = displayHeight
            }

            //get scaling factors
            val scaleX = finalWidth.toFloat() / width.toFloat()
            val scaleY = finalHeight.toFloat() / height.toFloat()

            val matrix = Matrix()

            matrix.postScale(scaleX, scaleY)
            //merge matrix and offset inside a pair
            return Pair(matrix, Size(displayWidth - finalWidth, displayHeight - finalHeight))
        }

        /**
         * Picks a random image and checks if the image ratio fits the display ratio.
         *
         * @return a loaded [Bitmap] fitting for the display ratio.
         */
        private fun pickImage(): Bitmap {
            //TODO stop looping when no image with a fitting ration is present
            //picks random image
            var post = pickRandomPost()
            //load image bounds from file
            var bounds = getBounds(post.file.absolutePath)
            //landscape or portray?
            if (displayWidth > displayHeight) {
                //while not fitting try new image
                while (bounds.width < bounds.height) {
                    post = pickRandomPost()
                    bounds = getBounds(post.file.absolutePath)
                }
            } else {
                //while not fitting try new image
                while (bounds.width > bounds.height) {
                    post = pickRandomPost()
                    bounds = getBounds(post.file.absolutePath)
                }
            }
            val options = BitmapFactory.Options()
            if (bounds.width > displayWidth * 2 && bounds.height > displayHeight * 2)
                options.inSampleSize = 2
            return BitmapFactory.decodeFile(post.file.absolutePath, options)
        }

        /**
         * Picks a random image from the image list.
         *
         * @return a random post.
         */
        private fun pickRandomPost(): DownloadedPost {
            val postList = FileManager.getAllDownloadedPosts()
            var i = (Math.random() * postList.size).toInt()
            return postList[i]
        }

        /**
         * Load the image bounds from a given path.
         *
         * @param path to the image
         * @return size of the image
         */
        private fun getBounds(path: String): Size {
            //don't load whole image just bounds
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true

            BitmapFactory.decodeFile(path, options)

            return Size(options.outWidth, options.outHeight)
        }
    }
}
