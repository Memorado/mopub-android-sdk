package com.mopub.mobileads;

import android.content.Context;
import android.graphics.Point;
import android.net.http.AndroidHttpClient;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.view.Display;
import android.view.WindowManager;

import com.mopub.common.HttpClient;
import com.mopub.common.Preconditions;
import com.mopub.common.VisibleForTesting;
import com.mopub.common.logging.MoPubLog;
import com.mopub.common.util.Dips;
import com.mopub.common.util.Strings;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static com.mopub.network.TrackingRequest.makeVastTrackingHttpRequest;


/**
 * AsyncTask that reads in VAST xml and resolves redirects. This returns a
 * fully formed {@link VastVideoConfiguration} so that the video can be
 * displayed with the settings and trackers set in the configuration.
 */
public class VastXmlManagerAggregator extends AsyncTask<String, Void, VastVideoConfiguration> {

    /**
     * Listener for when the xml parsing is done.
     */
    interface VastXmlManagerAggregatorListener {
        /**
         * When all the wrappers have resolved and aggregation is done, this passes in
         * a video configuration or null if one is not found.
         * @param vastVideoConfiguration The video configuration found or null if
         *                               no video was found.
         */
        void onAggregationComplete(final @Nullable VastVideoConfiguration vastVideoConfiguration);
    }

    // More than reasonable number of nested VAST urls to follow
    static final int MAX_TIMES_TO_FOLLOW_VAST_REDIRECT = 10;
    private static final double ASPECT_RATIO_WEIGHT = 40;
    private static final double AREA_WEIGHT = 60;
    private static final List<String> VIDEO_MIME_TYPES =
            Arrays.asList("video/mp4", "video/3gpp");

    @NonNull private final WeakReference<VastXmlManagerAggregatorListener> mVastXmlManagerAggregatorListener;
    private final double mScreenAspectRatio;
    private final int mScreenArea;
    @NonNull private final Context mContext;

    /**
     * Number of times this has followed a redirect. This value is only
     * accessed and set on the background thread.
     */
    private int mTimesFollowedVastRedirect;

    VastXmlManagerAggregator(@NonNull final VastXmlManagerAggregatorListener vastXmlManagerAggregatorListener,
            final double screenAspectRatio,
            final int screenArea,
            @NonNull final Context context) {
        super();

        Preconditions.checkNotNull(vastXmlManagerAggregatorListener);
        Preconditions.checkNotNull(context);
        mVastXmlManagerAggregatorListener =
                new WeakReference<VastXmlManagerAggregatorListener>(vastXmlManagerAggregatorListener);
        mScreenAspectRatio = screenAspectRatio;
        mScreenArea = screenArea;
        mContext = context.getApplicationContext();
    }

    @Override
    protected VastVideoConfiguration doInBackground(@Nullable String... strings) {
        AndroidHttpClient httpClient = null;
        try {
            httpClient = HttpClient.getHttpClient();
            if (strings != null && strings.length > 0) {
                String vastXml = strings[0];
                if (vastXml == null) {
                    return null;
                }
                return evaluateVastXmlManager(vastXml, httpClient, new ArrayList<VastTracker>());
            }
        } catch (Exception e) {
            MoPubLog.d("Failed to parse VAST XML", e);
        } finally {
            if (httpClient != null) {
                httpClient.close();
            }
        }

        return null;
    }

    @Override
    protected void onPostExecute(final @Nullable VastVideoConfiguration vastVideoConfiguration) {
        final VastXmlManagerAggregatorListener listener = mVastXmlManagerAggregatorListener.get();
        if (listener != null) {
            listener.onAggregationComplete(vastVideoConfiguration);
        }
    }

    @Override
    protected void onCancelled() {
        final VastXmlManagerAggregatorListener listener = mVastXmlManagerAggregatorListener.get();
        if (listener != null) {
            listener.onAggregationComplete(null);
        }
    }

    /**
     * Recursively traverses the VAST xml documents and finds the first Linear creative containing a
     * valid media file. For each Ad element in a document, the method will first try to find a
     * valid Linear creative in the InLine element. If it does not find one, it will then try to
     * resolve the Wrapper element which should redirect to more VAST xml documents with more InLine
     * elements.
     * <p/>
     * The list of error trackers are propagated through each wrapper redirect. If at the end of the
     * wrapper chain, there's no ad, then the error trackers for the entire wrapper chain are fired.
     * If a valid video is found, the error trackers are stored in the video configuration for
     * non-xml errors.
     *
     * @param vastXml           The xml that this class parses
     * @param androidHttpClient This is used to follow redirects
     * @param errorTrackers     This is the current list of error tracker URLs to hit if something
     *                          goes wrong.
     * @return {@link VastVideoConfiguration} with all available fields set or null if the xml is
     * invalid or null.
     */
    @VisibleForTesting
    @Nullable
    VastVideoConfiguration evaluateVastXmlManager(@NonNull final String vastXml,
            @NonNull final AndroidHttpClient androidHttpClient,
            @NonNull final List<VastTracker> errorTrackers) {
        Preconditions.checkNotNull(vastXml, "vastXml cannot be null");
        Preconditions.checkNotNull(androidHttpClient, "androidHttpClient cannot be null");
        Preconditions.checkNotNull(errorTrackers, "errorTrackers cannot be null");

        final VastXmlManager xmlManager = new VastXmlManager();
        try {
            xmlManager.parseVastXml(vastXml);
        } catch (Exception e) {
            MoPubLog.d("Failed to parse VAST XML", e);
            makeVastTrackingHttpRequest(errorTrackers, VastErrorCode.XML_PARSING_ERROR, null,
                    null, mContext);
            return null;
        }

        List<VastAdXmlManager> vastAdXmlManagers = xmlManager.getAdXmlManagers();

        // If there are no ads, fire the error trackers
        if (fireErrorTrackerIfNoAds(vastAdXmlManagers, xmlManager, mContext)) {
            return null;
        }

        for (VastAdXmlManager vastAdXmlManager : vastAdXmlManagers) {

            if (!isValidSequenceNumber(vastAdXmlManager.getSequence())) {
                continue;
            }

            // InLine evaluation
            VastInLineXmlManager vastInLineXmlManager = vastAdXmlManager.getInLineXmlManager();
            if (vastInLineXmlManager != null) {
                VastVideoConfiguration vastVideoConfiguration = evaluateInLineXmlManager(
                        vastInLineXmlManager, errorTrackers);
                // If the vastVideoConfiguration is non null, it means we found a valid media file
                if (vastVideoConfiguration != null) {
                    populateMoPubCustomElements(xmlManager, vastVideoConfiguration);
                    return vastVideoConfiguration;
                }
            }

            // Wrapper evaluation
            VastWrapperXmlManager vastWrapperXmlManager = vastAdXmlManager.getWrapperXmlManager();
            if (vastWrapperXmlManager != null) {
                final List<VastTracker> wrapperErrorTrackers = new ArrayList<VastTracker>(errorTrackers);
                wrapperErrorTrackers.addAll(vastWrapperXmlManager.getErrorTrackers());
                String vastRedirectXml = evaluateWrapperRedirect(vastWrapperXmlManager,
                        androidHttpClient, wrapperErrorTrackers);
                if (vastRedirectXml == null) {
                    continue;
                }

                VastVideoConfiguration vastVideoConfiguration = evaluateVastXmlManager(
                        vastRedirectXml,
                        androidHttpClient,
                        wrapperErrorTrackers);
                // If we don't find a valid video creative somewhere down this wrapper chain,
                // look at the next Ad element
                // NOTE: Wrapper elements will never contain media files according to the VAST
                // 3.0 spec
                if (vastVideoConfiguration == null) {
                    continue;
                }

                // If we have a vastVideoConfiguration it means that we found a valid media file
                // in one of Wrapper redirects. Therefore, aggregate all trackers in the wrapper
                vastVideoConfiguration.addImpressionTrackers(
                        vastWrapperXmlManager.getImpressionTrackers());
                List<VastLinearXmlManager> linearXmlManagers =
                        vastWrapperXmlManager.getLinearXmlManagers();
                for (VastLinearXmlManager linearXmlManager : linearXmlManagers) {
                    populateLinearTrackersAndIcon(linearXmlManager, vastVideoConfiguration);
                }

                // Only populate a companion ad if we don't already have one from one of the
                // redirects
                final VastCompanionAd companionAd = vastVideoConfiguration.getVastCompanionAd();
                if (companionAd == null) {
                    vastVideoConfiguration.setVastCompanionAd(
                            getBestCompanionAd(vastWrapperXmlManager.getCompanionAdXmlManagers()));
                } else {
                    // Otherwise append the companion trackers if it doesn't have resources
                    for (final VastCompanionAdXmlManager companionAdXmlManager : vastWrapperXmlManager.getCompanionAdXmlManagers()) {
                        if (!companionAdXmlManager.hasResources()) {
                            companionAd.addClickTrackers(companionAdXmlManager.getClickTrackers());
                            companionAd.addCreativeViewTrackers(
                                    companionAdXmlManager.getCompanionCreativeViewTrackers());
                        }
                    }
                }

                populateMoPubCustomElements(xmlManager, vastVideoConfiguration);

                return vastVideoConfiguration;
            }
        }

        return null;
    }

    /**
     * Parses and evaluates an InLine element looking for a valid media file. InLine elements are
     * evaluated in order and the first valid media file found is used. If a media file is
     * found, a {@link VastVideoConfiguration} is created and trackers are aggregated. If a
     * valid companion ad is found, it is also added to the configuration.
     *
     * @param vastInLineXmlManager used to extract the media file, clickthrough link, trackers, and
     *                         companion ad
     * @param errorTrackers The error trackers from previous wrappers
     * @return a {@link VastVideoConfiguration} or null if a valid media file was not found
     */
    @Nullable
    private VastVideoConfiguration evaluateInLineXmlManager(
            @NonNull final VastInLineXmlManager vastInLineXmlManager,
            @NonNull final List<VastTracker> errorTrackers) {
        Preconditions.checkNotNull(vastInLineXmlManager);

        List<VastLinearXmlManager> linearXmlManagers = vastInLineXmlManager.getLinearXmlManagers();
        for (VastLinearXmlManager linearXmlManager : linearXmlManagers) {
            String bestMediaFileUrl = getBestMediaFileUrl(linearXmlManager.getMediaXmlManagers());
            if (bestMediaFileUrl != null) {
                // Create vast video configuration and populate initial trackers
                VastVideoConfiguration vastVideoConfiguration = new VastVideoConfiguration();
                vastVideoConfiguration.addImpressionTrackers(vastInLineXmlManager.getImpressionTrackers());
                populateLinearTrackersAndIcon(linearXmlManager, vastVideoConfiguration);

                // Linear nodes will only have a click through url and network media file when they
                // are under an InLine element. They will not have these assets when they are under
                // a Wrapper element.
                vastVideoConfiguration.setClickThroughUrl(linearXmlManager.getClickThroughUrl());
                vastVideoConfiguration.setNetworkMediaFileUrl(bestMediaFileUrl);

                vastVideoConfiguration.setVastCompanionAd(getBestCompanionAd(vastInLineXmlManager
                        .getCompanionAdXmlManagers()));
                errorTrackers.addAll(vastInLineXmlManager.getErrorTrackers());
                vastVideoConfiguration.addErrorTrackers(errorTrackers);
                return vastVideoConfiguration;
            }
        }

        return null;
    }

    /**
     * Retrieves the Wrapper's redirect uri and follows it to return the next VAST xml String.
     *
     * @param vastWrapperXmlManager used to get the redirect uri
     * @param androidHttpClient     the http client
     * @param wrapperErrorTrackers  Error trackers to hit if something goes wrong
     * @return the next VAST xml String or {@code null} if it could not be resolved
     */
    @Nullable
    private String evaluateWrapperRedirect(@NonNull VastWrapperXmlManager vastWrapperXmlManager,
            @NonNull AndroidHttpClient androidHttpClient,
            @NonNull List<VastTracker> wrapperErrorTrackers) {
        String vastAdTagUri = vastWrapperXmlManager.getVastAdTagURI();
        if (vastAdTagUri == null) {
            return null;
        }

        String vastRedirectXml = null;
        try {
            vastRedirectXml = followVastRedirect(androidHttpClient, vastAdTagUri);
        } catch (Exception e) {
            MoPubLog.d("Failed to follow VAST redirect", e);
            if (!wrapperErrorTrackers.isEmpty()) {
                makeVastTrackingHttpRequest(wrapperErrorTrackers, VastErrorCode.WRAPPER_TIMEOUT,
                                null, null, mContext);
            }
        }

        return vastRedirectXml;
    }

    /**
     * This method aggregates all trackers found in the linearXmlManager and adds them to the
     * {@link VastVideoConfiguration}. This method also populates the skip offset and icon if they
     * have not already been populated in one of the wrapper redirects.
     *
     * @param linearXmlManager used to retrieve trackers, and assets
     * @param vastVideoConfiguration modified in this method to store trackers and assets
     */
    private void populateLinearTrackersAndIcon(@NonNull final VastLinearXmlManager linearXmlManager,
            @NonNull final VastVideoConfiguration vastVideoConfiguration) {
        Preconditions.checkNotNull(linearXmlManager, "linearXmlManager cannot be null");
        Preconditions.checkNotNull(vastVideoConfiguration, "vastVideoConfiguration cannot be null");

        vastVideoConfiguration.addAbsoluteTrackers(linearXmlManager.getAbsoluteProgressTrackers());
        vastVideoConfiguration.addFractionalTrackers(
                linearXmlManager.getFractionalProgressTrackers());
        vastVideoConfiguration.addPauseTrackers(linearXmlManager.getPauseTrackers());
        vastVideoConfiguration.addResumeTrackers(linearXmlManager.getResumeTrackers());
        vastVideoConfiguration.addCompleteTrackers(linearXmlManager.getVideoCompleteTrackers());
        vastVideoConfiguration.addCloseTrackers(linearXmlManager.getVideoCloseTrackers());
        vastVideoConfiguration.addSkipTrackers(linearXmlManager.getVideoSkipTrackers());
        vastVideoConfiguration.addClickTrackers(linearXmlManager.getClickTrackers());

        // Only set the skip offset if we haven't set it already in one of the redirects
        if (vastVideoConfiguration.getSkipOffset() == null) {
            vastVideoConfiguration.setSkipOffset(linearXmlManager.getSkipOffset());
        }

        // Only set the icon if we haven't set it already in one of the redirects
        if (vastVideoConfiguration.getVastIcon() == null) {
            vastVideoConfiguration.setVastIcon(getBestIcon(linearXmlManager.getIconXmlManagers()));
        }
    }

    /**
     * Parses all custom MoPub specific custom extensions and impression trackers
     * and populates them in the {@link VastVideoConfiguration}. These extensions are not part
     * of the Vast 3.0 spec and are appended to the root of the xml document.
     *
     * @param xmlManager used to retrieve the custom extensions and impression trackers
     * @param vastVideoConfiguration modified in this method to store custom extensions and
     *                               impression trackers
     */
    private void populateMoPubCustomElements(@NonNull final VastXmlManager xmlManager,
            @NonNull final VastVideoConfiguration vastVideoConfiguration) {
        Preconditions.checkNotNull(xmlManager, "xmlManager cannot be null");
        Preconditions.checkNotNull(vastVideoConfiguration, "vastVideoConfiguration cannot be null");

        vastVideoConfiguration.addImpressionTrackers(xmlManager.getMoPubImpressionTrackers());

        if (vastVideoConfiguration.getCustomCtaText() == null) {
            vastVideoConfiguration.setCustomCtaText(xmlManager.getCustomCtaText());
        }
        if (vastVideoConfiguration.getCustomSkipText() == null) {
            vastVideoConfiguration.setCustomSkipText(xmlManager.getCustomSkipText());
        }
        if (vastVideoConfiguration.getCustomCloseIconUrl() == null) {
            vastVideoConfiguration.setCustomCloseIconUrl(xmlManager.getCustomCloseIconUrl());
        }
        if (!vastVideoConfiguration.isCustomForceOrientationSet()) {
            vastVideoConfiguration.setCustomForceOrientation(xmlManager.getCustomForceOrientation());
        }
    }

    /**
     * Fires the available error tracker if the sole element in this vast document is an Error
     * element. In the VAST 3.0 spec in section 2.4.2.4, the No Ad Response can be represented by a
     * VAST document with only the Error element and no Ad elements. Returns whether or not the
     * error tracker was fired.
     *
     * @param vastAdXmlManagers The List of AdXmlManagers to determine if there are any ads
     *                          available
     * @param xmlManager        The current VastXmlManager that's used to get the new error tracker
     * @param context           Used to send an http request
     * @return {@code true} if the error tracker was fired, {@code false} if the error tracker was
     * not fired.
     */
    private boolean fireErrorTrackerIfNoAds(
            @NonNull final List<VastAdXmlManager> vastAdXmlManagers,
            @NonNull final VastXmlManager xmlManager, @NonNull Context context) {
        // When there is no <Ad> tag and when there is an error tracker
        if (vastAdXmlManagers.isEmpty() && xmlManager.getErrorTracker() != null ) {
            // Only use NO_ADS_VAST_RESPONSE if we've followed one or more wrappers
            makeVastTrackingHttpRequest(
                    Collections.singletonList(xmlManager.getErrorTracker()),
                    mTimesFollowedVastRedirect > 0
                            ? VastErrorCode.NO_ADS_VAST_RESPONSE
                            : VastErrorCode.UNDEFINED_ERROR,
                    null, null, context);
            return true;
        }
        return false;
    }

    @VisibleForTesting
    @Nullable
    String getBestMediaFileUrl(@NonNull final List<VastMediaXmlManager> managers) {
        Preconditions.checkNotNull(managers, "managers cannot be null");
        final List<VastMediaXmlManager> mediaXmlManagers = new ArrayList<VastMediaXmlManager>(managers);
        double bestMediaFitness = Double.POSITIVE_INFINITY;
        String bestMediaFileUrl = null;

        final Iterator<VastMediaXmlManager> xmlManagerIterator = mediaXmlManagers.iterator();
        while (xmlManagerIterator.hasNext()) {
            final VastMediaXmlManager mediaXmlManager = xmlManagerIterator.next();

            final String mediaType = mediaXmlManager.getType();
            final String mediaUrl = mediaXmlManager.getMediaUrl();
            if (!VIDEO_MIME_TYPES.contains(mediaType) || mediaUrl == null) {
                xmlManagerIterator.remove();
                continue;
            }

            final Integer mediaWidth = mediaXmlManager.getWidth();
            final Integer mediaHeight = mediaXmlManager.getHeight();
            if (mediaWidth == null || mediaWidth <= 0 || mediaHeight == null || mediaHeight <= 0) {
                continue;
            }

            final double mediaFitness = calculateFitness(mediaWidth, mediaHeight);
            if (mediaFitness < bestMediaFitness) {
                bestMediaFitness = mediaFitness;
                bestMediaFileUrl = mediaUrl;
            }
        }

        return bestMediaFileUrl;
    }

    @VisibleForTesting
    @Nullable
    VastCompanionAd getBestCompanionAd(
            @NonNull final List<VastCompanionAdXmlManager> managers) {
        Preconditions.checkNotNull(managers, "managers cannot be null");
        final List<VastCompanionAdXmlManager> companionXmlManagers =
                new ArrayList<VastCompanionAdXmlManager>(managers);
        double bestCompanionFitness = Double.POSITIVE_INFINITY;
        VastCompanionAdXmlManager bestCompanionXmlManager = null;
        VastResource bestVastResource = null;
        Point bestVastScaledDimensions = null;

        // Look for the best companion ad in order of prioritized resource types
        for (VastResource.Type type : VastResource.Type.values()) {
            final Iterator<VastCompanionAdXmlManager> xmlManagerIterator =
                    companionXmlManagers.iterator();
            while (xmlManagerIterator.hasNext()) {
                final VastCompanionAdXmlManager companionXmlManager = xmlManagerIterator.next();

                final Integer width = companionXmlManager.getWidth();
                final Integer height = companionXmlManager.getHeight();
                if (width == null || width <= 0 ||
                        height == null || height <= 0) {
                    continue;
                }

                Point vastScaledDimensions = getScaledDimensions(width, height);
                VastResource vastResource = VastResource.fromVastResourceXmlManager(
                        companionXmlManager.getResourceXmlManager(), type,
                        vastScaledDimensions.x, vastScaledDimensions.y);
                if (vastResource == null) {
                    continue;
                }

                final double companionFitness = calculateFitness(width, height);
                if (companionFitness < bestCompanionFitness) {
                    bestCompanionFitness = companionFitness;
                    bestCompanionXmlManager = companionXmlManager;
                    bestVastResource = vastResource;
                    bestVastScaledDimensions = vastScaledDimensions;
                }
            }
            if (bestCompanionXmlManager != null) {
                break;
            }
        }

        if (bestCompanionXmlManager != null) {
            return new VastCompanionAd(
                    bestVastScaledDimensions.x,
                    bestVastScaledDimensions.y,
                    bestVastResource,
                    bestCompanionXmlManager.getClickThroughUrl(),
                    bestCompanionXmlManager.getClickTrackers(),
                    bestCompanionXmlManager.getCompanionCreativeViewTrackers()
            );
        }
        return null;
    }

    /**
     * Given a width and height for a resource, if the dimensions are larger than the screen size
     * then scale them down to fit in the screen while maintaining the aspect ratio. Scaling
     * takes into account the default Android WebView padding.
     *
     * @param widthDp width of the resource in dips
     * @param heightDp height of the resource in dips
     * @return the new scaled dimensions that honor the aspect ratio
     */
    @VisibleForTesting
    @NonNull
    Point getScaledDimensions(int widthDp, int heightDp) {
        Point defaultPoint = new Point(widthDp, heightDp);
        final Display display = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int x = display.getWidth();
        int y = display.getHeight();

        // For landscape, width is always greater than height
        int screenWidth = Math.max(x, y);
        int screenHeight = Math.min(x, y);

        int widthPx = Dips.dipsToIntPixels(widthDp, mContext);
        int heightPx = Dips.dipsToIntPixels(heightDp, mContext);

        // Return if the width and height already fit in the screen
        if (widthPx <= screenWidth && heightPx <= screenHeight) {
            return defaultPoint;
        }

        float widthRatio = (float) widthPx / screenWidth;
        float heightRatio = (float) heightPx / screenHeight;

        Point point = new Point();
        if (widthRatio >= heightRatio) {
            point.x = screenWidth - VastVideoViewController.WEBVIEW_PADDING;
            point.y = (int) (heightPx / widthRatio) - VastVideoViewController.WEBVIEW_PADDING;
        } else {
            point.x = (int) (widthPx / heightRatio) - VastVideoViewController.WEBVIEW_PADDING;
            point.y = screenHeight - VastVideoViewController.WEBVIEW_PADDING;
        }

        if (point.x < 0 || point.y < 0) {
            return defaultPoint;
        }

        point.x = Dips.pixelsToIntDips(point.x, mContext);
        point.y = Dips.pixelsToIntDips(point.y, mContext);

        return point;
    }

    @VisibleForTesting
    @Nullable
    VastIcon getBestIcon(@NonNull final List<VastIconXmlManager> managers) {
        Preconditions.checkNotNull(managers, "managers cannot be null");
        final List<VastIconXmlManager> iconXmlManagers = new ArrayList<VastIconXmlManager>(managers);

        // Look for the best icon in order of prioritized resource types
        for (VastResource.Type type : VastResource.Type.values()) {
            final Iterator<VastIconXmlManager> xmlManagerIterator = iconXmlManagers.iterator();
            while (xmlManagerIterator.hasNext()) {
                final VastIconXmlManager iconXmlManager = xmlManagerIterator.next();

                final Integer width = iconXmlManager.getWidth();
                final Integer height = iconXmlManager.getHeight();

                // Icons can be a max of 300 x 300 dp
                if (width == null || width <= 0 || width > 300
                        || height == null || height <= 0 || height > 300) {
                    continue;
                }

                VastResource vastResource = VastResource.fromVastResourceXmlManager(
                        iconXmlManager.getResourceXmlManager(), type, width, height);

                if (vastResource == null) {
                    continue;
                }

                return new VastIcon(
                        iconXmlManager.getWidth(),
                        iconXmlManager.getHeight(),
                        iconXmlManager.getOffsetMS(),
                        iconXmlManager.getDurationMS(),
                        vastResource,
                        iconXmlManager.getClickTrackingUris(),
                        iconXmlManager.getClickThroughUri(),
                        iconXmlManager.getViewTrackingUris());
            }
        }

        return null;
    }

    /**
     * Calculates the fitness of the media file or companion by comparing its aspect ratio and
     * area to those of the device. The closer to 0 the score, the better. The fitness function
     * weighs aspect ratios and areas differently.
     *
     * @param width the width of the media file or companion ad
     * @param height the height of th media file or companion ad
     * @return the fitness score. The closer to 0, the better.
     */
    private double calculateFitness(final int width, final int height) {
        final double mediaAspectRatio = (double) width / height;
        final int mediaArea = width * height;
        final double aspectRatioRatio = mediaAspectRatio / mScreenAspectRatio;
        final double areaRatio = (double) mediaArea / mScreenArea;
        return ASPECT_RATIO_WEIGHT * Math.abs(Math.log(aspectRatioRatio))
                + AREA_WEIGHT * Math.abs(Math.log(areaRatio));
    }

    /**
     * Since MoPub does not support ad pods, do not accept any positive integers greater than 1.
     * MoPub will use the first ad in an ad pod (sequence = 1), but it will ignore all other ads in
     * the pod. If no sequence number, MoPub treats it like a stand-alone ad. If the sequence number
     * is nonsensical (e.g. negative, fails to parse as an integer), MoPub treats it like a
     * stand-alone ad.
     *
     * @param sequence The sequence number
     * @return True if this is a sequence number that MoPub would show an ad for, false if not.
     */
    static boolean isValidSequenceNumber(@Nullable final String sequence) {
        if (TextUtils.isEmpty(sequence)) {
            return true;
        }
        try {
            final int sequenceInt = Integer.parseInt(sequence);
            return sequenceInt < 2;
        } catch (NumberFormatException e) {
            // Since the sequence number is not a valid integer, go ahead and pretend there's no
            // sequence number and show this ad
            return true;
        }
    }

    @Nullable
    private String followVastRedirect(@NonNull final AndroidHttpClient httpClient,
            @NonNull final String redirectUrl) throws Exception {
        Preconditions.checkNotNull(httpClient);
        Preconditions.checkNotNull(redirectUrl);

        if (mTimesFollowedVastRedirect < MAX_TIMES_TO_FOLLOW_VAST_REDIRECT) {
            mTimesFollowedVastRedirect++;

            final HttpGet httpget = HttpClient.initializeHttpGet(redirectUrl);
            final HttpResponse response = httpClient.execute(httpget);
            final HttpEntity entity = response.getEntity();
            return (entity != null) ? Strings.fromStream(entity.getContent()) : null;
        }
        return null;
    }

    @VisibleForTesting
    @Deprecated
    void setTimesFollowedVastRedirect(final int timesFollowedVastRedirect) {
        mTimesFollowedVastRedirect = timesFollowedVastRedirect;
    }
}
