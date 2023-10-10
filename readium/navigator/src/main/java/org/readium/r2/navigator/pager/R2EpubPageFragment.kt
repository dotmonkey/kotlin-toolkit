/*
 * Module: r2-navigator-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann, Mostapha Idoubihi, Paul Stoica
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.navigator.pager

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.view.*
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.R
import org.readium.r2.navigator.R2BasicWebView
import org.readium.r2.navigator.R2WebView
import org.readium.r2.navigator.databinding.ViewpagerFragmentEpubBinding
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.extensions.htmlId
import org.readium.r2.shared.SCROLL_REF
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression
import org.readium.r2.shared.publication.html.domRange
import org.readium.r2.shared.publication.html.partialCfi
import org.readium.r2.shared.publication.isFinished
import java.io.IOException
import java.io.InputStream
import java.util.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

fun getLocaleName(locale: Locale): String {
    var script = locale.script
    if(script.isEmpty()){
        when(locale.country){
            "SG",
            "CHS",
            "CN"->{
                script = "Hans"
            }
            "MO",
            "HK",
            "CHT",
            "TW"->{
                script = "Hant"
            }
        }
    }
    var res = "${locale.language}_${script}"

    when(locale.language){
        "zh"->{
        }
        "en"->{
            res = "en"
        }
        else->{
            res = "zh_Hans"
        }
    }
    return res
}
fun Link.getTitle(h:String): String? {
    for(ch in children){
        val t = ch.getTitle(h)
        if(t!=null) {
            return t
        }
    }
    if(href?.substringBefore('#')==h) return title
    return null
}
fun Publication.getTitle(href:String?): String? {
    if(href==null) return null
    val file = href.substringBefore('#')
    for(l in tableOfContents){
        val t = l.getTitle(file)
        if(t!=null) {
            return t
        }
    }
    return null
}
class R2EpubPageFragment : Fragment() {

    private val resourceUrl: String?
        get() = requireArguments().getString("url")

    internal val link: Link?
        get() = requireArguments().getParcelable("link")

    private val positionCount: Long
        get() = requireArguments().getLong("positionCount")

    var webView: R2WebView? = null
        private set

    val chapterTitle:String
    get() {
        val navigatorFragment = parentFragment as EpubNavigatorFragment
        val pub = navigatorFragment.publication
        return pub.getTitle(link!!.href) ?: ""
    }
    private lateinit var containerView: View
    private lateinit var preferences: SharedPreferences

    private var _binding: ViewpagerFragmentEpubBinding? = null
    private val binding get() = _binding!!

    private var isLoading: Boolean = false
    private lateinit var progressRange: ClosedFloatingPointRange<Double>
    fun getStatusBarHeight(context: Context): Int {
        val resources = context.resources
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else ceil((if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) 24 else 25) * resources.displayMetrics.density)
            .toInt()
    }
    val isFinishedPage:Boolean
        get() {
            return resourceUrl=="http://localhost/android_asset/endao/html/finished.html"
        }
    fun layoutParam(): String {
        val obj = JSONObject()
        val loc = ConfigurationCompat.getLocales(resources.configuration)[0]
        obj.put("chapterTitle", chapterTitle)
        obj.put("language", getLocaleName(loc))
        obj.put("progressStart", progressRange.start)
        obj.put("progressEnd", progressRange.endInclusive)
        if(isFinishedPage){
            val epubNavigator = requireNotNull(webView?.navigator as? EpubNavigatorFragment)
            val locator = epubNavigator.currentLocator.value
            obj.put("finished",locator.isFinished())
            val bid = epubNavigator.config.bookId
            obj.put("bookId",bid)
        }
        return obj.toString(0)
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val navigatorFragment = parentFragment as EpubNavigatorFragment
        _binding = ViewpagerFragmentEpubBinding.inflate(inflater, container, false)
        containerView = binding.root
        preferences = activity?.getSharedPreferences("org.readium.r2.settings", Context.MODE_PRIVATE)!!

        progressRange = navigatorFragment.progressRange(link!!)
        val webView = binding.webView
        val cb = navigatorFragment.config.webViewCallback
        webView.webViewCallback = cb
        this.webView = webView

        webView.visibility = View.INVISIBLE
        webView.navigator = navigatorFragment
        webView.listener = navigatorFragment.webViewListener
        webView.preferences = preferences

        //webView.setScrollMode(preferences.getBoolean(SCROLL_REF, false))
        webView.settings.javaScriptEnabled = true
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.resourceUrl = resourceUrl
        webView.setPadding(0, 0, 0, 0)

        var endReached = false
        webView.setOnOverScrolledCallback(object : R2BasicWebView.OnOverScrolledCallback {
            override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
                val activity = activity ?: return
                val metrics = DisplayMetrics()
                activity.windowManager.defaultDisplay.getMetrics(metrics)


                val topDecile = webView.contentHeight - 1.15 * metrics.heightPixels
                val bottomDecile = (webView.contentHeight - metrics.heightPixels).toDouble()

                when (scrollY.toDouble()) {
                    in topDecile..bottomDecile -> {
                        if (!endReached) {
                            endReached = true
                            webView.listener.onPageEnded(endReached)
                        }
                    }
                    else -> {
                        if (endReached) {
                            endReached = false
                            webView.listener.onPageEnded(endReached)
                        }
                    }
                }
            }
        })

        webView.webViewClient = object : WebViewClientCompat() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                (webView as? R2BasicWebView)?.shouldOverrideUrlLoading(request) ?: false

            override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent): Boolean {
                // Do something with the event here
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                val window = activity?.window!!
                val rectangle = Rect()
                window.decorView.getWindowVisibleDisplayFrame(rectangle)
                var top = getStatusBarHeight(view.context)
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P){
                    val cutout = window.decorView.rootWindowInsets?.displayCutout
                    if(cutout!=null){
                        top = maxOf(cutout.safeInsetTop)
                    }
                }

                updatePadding()
                //webView.evaluateJavascript("endao.setProgressRange(${progressRange.start},${progressRange.endInclusive})"){}
                webView.listener.onResourceLoaded(link, webView, url)

                /*
                val loc = ConfigurationCompat.getLocales(resources.configuration)[0]
                val obj = JSONObject()
                obj.put("chapterTitle", chapterTitle)
                obj.put("language", getLocaleName(loc))
                obj.put("progressStart", progressRange.start)
                obj.put("progressEnd", progressRange.endInclusive)
                val json = obj.toString(0)
                */
                val json = layoutParam()
                // To make sure the page is properly laid out before jumping to the target locator,
                // we execute a dummy JavaScript and wait for the callback result.

                webView.evaluateJavascript("true") {
                    webView.evaluateJavascript("endao.onload($json);"){}
                    onLoadPage()
                }
            }

            // prevent favicon.ico to be loaded, this was causing NullPointerException in NanoHttp
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.isForMainFrame && request.url.path?.endsWith("/favicon.ico") == true) {
                    try {
                        return WebResourceResponse("image/png", null, null)
                    } catch (e: Exception) {
                    }
                }
                if(cb!=null){
                    return cb.shouldInterceptRequest(view,request)
                }
                return null
            }

            private fun injectScriptFile(view: WebView?, scriptFile: String) {
                val input: InputStream
                try {
                    input = resources.assets.open(scriptFile)
                    val buffer = ByteArray(input.available())
                    input.read(buffer)
                    input.close()

                    // String-ify the script byte-array using BASE64 encoding !!!
                    val encoded = Base64.encodeToString(buffer, Base64.NO_WRAP)
                    view?.loadUrl("javascript:(function() {" +
                            "var parent = document.getElementsByTagName('head').item(0);" +
                            "var script = document.createElement('script');" +
                            "script.type = 'text/javascript';" +
                            // Tell the browser to BASE64-decode the string into your script !!!
                            "script.innerHTML = window.atob('" + encoded + "');" +
                            "parent.appendChild(script)" +
                            "})()")
                } catch (e: IOException) {
                    e.printStackTrace()
                } catch (e1: IllegalStateException) {
                    // not attached to a context
                }
            }
        }

        webView.isHapticFeedbackEnabled = false
        webView.isLongClickable = false
        webView.setOnLongClickListener {
            false
        }

        resourceUrl?.let {
            isLoading = true
            webView.loadUrl(it)
        }

        setupPadding()

        // Forward a tap event when the web view is not ready to propagate the taps. This allows
        // to toggle a navigation UI while a page is loading, for example.
        binding.root.setOnClickListenerWithPoint { _, point ->
            webView.listener.onTap(point)
        }

        return containerView
    }

    override fun onDetach() {
        super.onDetach()

        // Prevent the web view from leaking when its parent is detached.
        // See https://stackoverflow.com/a/19391512/1474476
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupPadding() {
        updatePadding()

        // Update padding when the scroll mode changes
        viewLifecycleOwner.lifecycleScope.launch {
            webView?.scrollModeFlow?.collectLatest {
                updatePadding()
            }
        }

        // Update padding when the window insets change, for example when the navigation and status
        // bars are toggled.
        ViewCompat.setOnApplyWindowInsetsListener(containerView) { _, insets ->
            updatePadding()
            insets
        }
    }

    private fun updatePadding() {
        viewLifecycleOwner.lifecycleScope.launchWhenResumed {
            val window = activity?.window ?: return@launchWhenResumed
            var top = 0
            var bottom = 0

            // Add additional padding to take into account the display cutout, if needed.
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                window.attributes.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            ) {
                // Request the display cutout insets from the decor view because the ones given by
                // setOnApplyWindowInsetsListener are not always correct for preloaded views.
                window.decorView.rootWindowInsets?.displayCutout?.let { displayCutoutInsets ->
                    top += displayCutoutInsets.safeInsetTop
                    bottom += displayCutoutInsets.safeInsetBottom
                }
            }

            val scrollMode = preferences.getBoolean(SCROLL_REF, false)
            if (!scrollMode) {
                //val margin = resources.getDimension(R.dimen.r2_navigator_epub_vertical_padding).toInt()
                top += resources.getDimension(R.dimen.r2_navigator_epub_vertical_padding_top).toInt()
                bottom += resources.getDimension(R.dimen.r2_navigator_epub_vertical_padding_bottom).toInt()
            }

            var sc = "document.documentElement.style.paddingTop=($top/window.devicePixelRatio)+\"px\";"
            sc += "document.documentElement.style.paddingBottom=($bottom/window.devicePixelRatio)+\"px\";"
            webView?.evaluateJavascript(sc){}
            //containerView.setPadding(0, top, 0, bottom)
        }
    }

    internal val paddingTop: Int get() = containerView.paddingTop
    internal val paddingBottom: Int get() = containerView.paddingBottom

    private val isCurrentResource: Boolean get() {
        val epubNavigator = webView?.navigator as? EpubNavigatorFragment ?: return false
        val currentFragment = (epubNavigator.resourcePager.adapter as? R2PagerAdapter)?.getCurrentFragment() as? R2EpubPageFragment ?: return false
        return tag == currentFragment.tag
    }

    private fun onLoadPage() {
        if (!isLoading) return
        isLoading = false

        if (view == null) return

        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            val webView = requireNotNull(webView)
            webView.visibility = View.VISIBLE

            val epubNavigator = requireNotNull(webView.navigator as? EpubNavigatorFragment)
            val locator = epubNavigator.pendingLocator
            val sch = epubNavigator.pendingSearch
            if (locator != null) {
                if(locator.href ==link?.href){
                    epubNavigator.pendingLocator = null
                    epubNavigator.pendingSearch = false
                    loadLocator(webView, epubNavigator.readingProgression, locator,sch)
                }
            }
            if (isCurrentResource) {
                webView.listener.onPageLoaded()
            }
        }
    }

    private suspend fun loadLocator(webView: R2WebView, readingProgression: ReadingProgression, locator: Locator, sch: Boolean) {
        val rng = locator.locations.domRange
        val cfi = locator.locations.partialCfi
        if(rng!=null || cfi!=null){
            if (webView.locate(locator,sch)) {
                return
            }
        }
        val htmlId = locator.locations.htmlId
        if (htmlId != null && webView.scrollToId(htmlId)) {
            return
        }
        val text = locator.text
        if (text.highlight != null) {
            if (webView.scrollToText(text)) {
                return
            }
        }

        var progression = locator.locations.progression
        if (progression != null) {
            // We need to reverse the progression with RTL because the Web View
            // always scrolls from left to right, no matter the reading direction.
            progression =
                if (webView.scrollMode || readingProgression == ReadingProgression.LTR) progression
                else 1 - progression

            if (webView.scrollMode) {
                webView.scrollToPosition(progression)

            } else {
                // Figure out the target web view "page" from the requested
                // progression.
                var item = (progression * webView.numPages).roundToInt()
                if (readingProgression == ReadingProgression.RTL && item > 0) {
                    item -= 1
                }
                webView.setCurrentItem(item, false)
            }
        }
    }

    companion object {
        fun newInstance(url: String, link: Link? = null, positionCount: Int = 0): R2EpubPageFragment =
            R2EpubPageFragment().apply {
                arguments = Bundle().apply {
                    putString("url", url)
                    putParcelable("link", link)
                    putLong("positionCount", positionCount.toLong())
                }
            }
    }
}

/**
 * Same as setOnClickListener, but will also report the tap point in the view.
 */
private fun View.setOnClickListenerWithPoint(action: (View, PointF) -> Unit) {
    var point = PointF()

    setOnTouchListener { v, event ->
        if (event.action == MotionEvent.ACTION_DOWN) {
            point = PointF(event.x, event.y)
        }
        false
    }

    setOnClickListener {
        action(it, point)
    }
}