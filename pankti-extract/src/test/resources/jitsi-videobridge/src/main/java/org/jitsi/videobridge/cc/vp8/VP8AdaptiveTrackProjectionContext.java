/*
 * Copyright @ 2019 8x8, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.cc.vp8;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.rtp.util.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.cc.*;
import org.jitsi_modified.impl.neomedia.codec.video.vp8.*;
import org.json.simple.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * This class represents a projection of a VP8 RTP stream in the RFC 7667 sense
 * and it is the main entry point for VP8 simulcast/svc RTP/RTCP rewriting. Read
 * svc.md for implementation details. Instances of this class are thread-safe.
 *
 * @author George Politis
 */
public class VP8AdaptiveTrackProjectionContext
    implements AdaptiveTrackProjectionContext
{
    private final Logger logger;

    /**
     * The time (in clock rate samples) to wait before discarding a
     * non-fully-projected frame (see {@link #cleanupFrameProjectionMap}).
     */
    private final long WAIT_SAMPLES = 45000;

    /**
     * A map of partially transmitted {@link VP8FrameProjection}s, i.e.
     * projections of VP8 frames for which we haven't transmitted all their
     * packets.
     *
     * Fully transmitted and skipped frames are removed from the map for
     * housekeeping purposes, i.e. to prevent the map from growing too big.
     *
     * The purpose of this map is to enable forwarding and translation of
     * recovered packets of partially transmitted frames and partially
     * transmitted frames _only_.
     *
     * Recovered packets of fully transmitted frames (this can happen for
     * example when the sending endpoint probes for bandwidth with duplicate
     * packets over the RTX stream) are dropped as they're not needed anymore.
     */
    private final SortedMap<Long, VP8FrameProjection>
        vp8FrameProjectionMap = new ConcurrentSkipListMap<>(
            /* This is only a valid Comparitor if timestamp diffs of all
             * timestamps are within half the number space.  (This property is
             * assured by #cleanupFrameProjectionMap.)
             */
            (t1, t2) -> (int)RtpUtils.getTimestampDiff(t1, t2));

    /**
     * A map that stores the maximum sequence number of frames that are not
     * (yet) accepted/projected. The map goes from ssrc -> timestamp -> highest
     * sequence number.
     */
    private final Map<Long, LRUCache<Long, Integer>>
        ssrcToFrameToMaxSequenceNumberMap = new HashMap<>();

    /**
     * The {@link VP8QualityFilter} instance that does quality filtering on the
     * incoming frames.
     */
    private final VP8QualityFilter vp8QualityFilter;

    /**
     * The diagnostic context of this instance.
     */
    private final DiagnosticContext diagnosticContext;

    /**
     * The "last" {@link VP8FrameProjection} that this instance has accepted.
     * In this context, last here means with the "highest extended picture id"
     * and not, for example, the last one received by the bridge.
     */
    private VP8FrameProjection lastVP8FrameProjection;

    /**
     * The VP8 media format. No essential functionality relies on this field,
     * it's only used as a cache of the {@link PayloadType} instance for VP8 in
     * case we have to do a context switch (see {@link AdaptiveTrackProjection}),
     * in order to avoid having to resolve the format.
     */
    private final PayloadType payloadType;

    /**
     * Ctor.
     *
     * @param payloadType the VP8 media format.
     * @param rtpState the RTP state to begin with.
     */
    public VP8AdaptiveTrackProjectionContext(
            @NotNull DiagnosticContext diagnosticContext,
            @NotNull PayloadType payloadType,
            @NotNull RtpState rtpState,
            @NotNull Logger parentLogger)
    {
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(VP8AdaptiveTrackProjectionContext.class.getName());
        this.payloadType = payloadType;
        this.vp8QualityFilter = new VP8QualityFilter(parentLogger);

        // Compute the starting sequence number and the timestamp of the initial
        // frame based on the RTP state.
        int startingSequenceNumber =
                RtpUtils.applySequenceNumberDelta(rtpState.maxSequenceNumber, 1);

        long timestamp =
                RtpUtils.applyTimestampDelta(rtpState.maxTimestamp, 3000);

        lastVP8FrameProjection = new VP8FrameProjection(diagnosticContext, logger,
            rtpState.ssrc, startingSequenceNumber, timestamp);
    }

    /**
     * Looks-up for an existing VP8 frame projection that corresponds to the
     * specified RTP packet.
     *
     * @param rtpPacket the RTP packet
     * @return an existing VP8 frame projection or null.
     */
    private VP8FrameProjection
    lookupVP8FrameProjection(@NotNull VideoRtpPacket rtpPacket)
    {
        // Lookup for an existing VP8 frame doesn't need to be synced because
        // we're using a ConcurrentHashMap. At the time of this writing, two
        // threads reach this point: the translator thread when it decides
        // whether to accept or drop a packet and the transformer thread when it
        // needs to rewrite a packet.

        VP8FrameProjection
            lastVP8FrameProjectionCopy = lastVP8FrameProjection;

        // First, check if this is a packet from the "last" VP8 frame.
        VP8Frame lastVP8Frame = lastVP8FrameProjectionCopy.getVP8Frame();

        // XXX we must check for null because the initial projection does not
        // have an associated frame.
        if (lastVP8Frame != null && lastVP8Frame.matchesFrame(rtpPacket))
        {
            return lastVP8FrameProjectionCopy;
        }

        // Check if this is a packet from a partially transmitted frame
        // (partially transmitted implies that the frame has been previously
        // accepted; the inverse does not necessarily hold).

        VP8FrameProjection cachedVP8FrameProjection
            = vp8FrameProjectionMap.get(rtpPacket.getTimestamp());

        if (cachedVP8FrameProjection != null)
        {
            VP8Frame cachedVP8Frame = cachedVP8FrameProjection.getVP8Frame();

            // XXX we match both the pkt timestamp *and* the pkt SSRC, as the
            // vp8FrameProjection may refer to a frame from another RTP stream.
            // In that case, we want to skip the return statement below.
            if (cachedVP8Frame != null && cachedVP8Frame.matchesFrame(rtpPacket))
            {
                return cachedVP8FrameProjection;
            }
        }

        return null;
    }

    /** Clean up old entries in the frame projection map.
     *
     * The frame projection map is maintained as a sorted map; to clean it up,
     * we walk entries from the oldest (in timestamp ordering), removing anything
     * more than #WAIT_SAMPLES older than the newest timestamp.
     *
     * Because the map is ordered, we can stop searching entries when we reach
     * one that is new enough not to be removed, so this is amortized constant
     * time per frame.
     *
     * Caller should be synchronized.
     * */
    private void cleanupFrameProjectionMap(long newTimestamp)
    {
        if (vp8FrameProjectionMap.isEmpty())
        {
            return;
        }

        long newestTimestamp = vp8FrameProjectionMap.lastKey();

        /* If our timestamps have jumped by a quarter of the number space, reset the map. */
        long timestampJump = RtpUtils.getTimestampDiff(newTimestamp, newestTimestamp);
        if (timestampJump >= 0x4000_0000 || timestampJump <= -0x4000_0000)
        {
            vp8FrameProjectionMap.clear();
            return;
        }

        long threshold = RtpUtils.applyTimestampDelta(newTimestamp, -WAIT_SAMPLES);

        Iterator<Long> it = vp8FrameProjectionMap.keySet().iterator();
        while (it.hasNext())
        {
            Long key = it.next();
            if (RtpUtils.isOlderTimestampThan(key, threshold))
            {
                it.remove();
            }
            else {
                break;
            }
        }
    }

    /**
     * Defines a packet filter that determines which packets to project in order
     * to produce an RTP stream that can be correctly be decoded at the receiver
     * as well as match, as close as possible, the changing quality target.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param rtpPacket the VP8 packet to decide whether or not to project.
     * @param incomingIndex the quality index of the incoming RTP packet
     * @param targetIndex the target quality index we want to achieve
     * @return true to project the packet, otherwise false.
     */
    private synchronized
    VP8FrameProjection createVP8FrameProjection(
        @NotNull VideoRtpPacket rtpPacket, int incomingIndex, int targetIndex)
    {
        // Creating a new VP8 projection depends on reading and results in
        // writing of the last VP8 frame, therefore this method needs to be
        // synced. At the time of this writing, only the translator thread is
        // reaches this point.

        VP8Frame lastVP8Frame = lastVP8FrameProjection.getVP8Frame();
        // Old VP8 frames cannot be accepted because there's no "free" space in
        // the sequence numbers. Check that before we create any structures to
        // support the incoming packet/frame.
        if (lastVP8Frame != null && lastVP8Frame.matchesOlderFrame(rtpPacket))
        {
            return null;
        }

        // if packet loss/re-ordering happened and this is not the first packet
        // of a frame, then we don't process it right now. It'll get its chance
        // when the first packet arrives and, if it's chosen for forwarding,
        // we'll piggy-back any missed packets.
        //
        // This is to keep things simple (i.e. make it easy to compute the
        // starting sequence number of the projection of an accepted frame).

        byte[] buf = rtpPacket.getBuffer();
        int payloadOff = rtpPacket.getPayloadOffset();
        if (!DePacketizer.VP8PayloadDescriptor.isStartOfFrame(buf, payloadOff))
        {
            maybeUpdateMaxSequenceNumberOfFrame(
                rtpPacket.getSsrc(),
                rtpPacket.getTimestamp(),
                rtpPacket.getSequenceNumber());
            return null;
        }

        long nowMs = System.currentTimeMillis();

        // Lastly, check whether the quality of the frame is something that we
        // want to forward. We don't want to be allocating new objects unless
        // we're interested in the quality of this frame.
        if (!vp8QualityFilter.acceptFrame(
            rtpPacket, incomingIndex, targetIndex, nowMs))
        {
            return null;
        }

        // We know we want to forward this frame, but we need to make sure it's
        // going to produce a decodable VP8 packet stream.
        int maxSequenceNumberSeenBeforeFirstPacket
            = getMaxSequenceNumberOfFrame(
                rtpPacket.getSsrc(), rtpPacket.getTimestamp());

        VP8FrameProjection nextVP8FrameProjection = lastVP8FrameProjection
            .makeNext(rtpPacket, maxSequenceNumberSeenBeforeFirstPacket, nowMs);

        if (nextVP8FrameProjection == null)
        {
            return null;
        }

        if (lastVP8FrameProjection.getSSRC() != nextVP8FrameProjection.getSSRC())
        {
            /* We've changed the encoding we're forwarding, so previous frames
             * in the map are no longer relevant.
             */
            vp8FrameProjectionMap.clear();
        }
        else
        {
            /* Remove projections of old frames from the map. */
            cleanupFrameProjectionMap(rtpPacket.getTimestamp());
        }

        // We have successfully projected the incoming frame and we've allocated
        // a starting sequence number for it. Any previous frames can no longer
        // grow.
        vp8FrameProjectionMap.put(rtpPacket.getTimestamp(), nextVP8FrameProjection);
        // The frame attached to the "last" projection is no longer the "last".
        lastVP8FrameProjection = nextVP8FrameProjection;

        return nextVP8FrameProjection;
    }

    /**
     * Given a frame (specified by the SSRC and the timestamp that are specified
     * as arguments), find the highest sequence number we've received from
     * that frame.
     *
     * @param ssrc the SSRC of the frame
     * @param timestamp the timestamp of the frame
     * @return the highest sequence number we've received from the frame that is
     * specified by the SSRC and timestamp arguments.
     */
    private int getMaxSequenceNumberOfFrame(long ssrc, long timestamp)
    {
        Map<Long, Integer> frameToMaxSequenceNumberMap
            = ssrcToFrameToMaxSequenceNumberMap.get(ssrc);

        if (frameToMaxSequenceNumberMap == null)
        {
            return -1;
        }

        return frameToMaxSequenceNumberMap
            .getOrDefault(timestamp, -1);
    }

    /**
     * Upon arrival of an RTP packet of a video frame (specified by its SSRC,
     * its timestamp and its sequence number that are specified as arguments),
     * elects and store the highest sequence number we've received from that
     * frame.
     *
     * @param ssrc the SSRC of the frame
     * @param timestamp the timestamp of the frame
     * @param sequenceNumber the sequence number of the RTP packet,
     * potentially the highest sequence number that we've received from that
     * frame.
     */
    private void maybeUpdateMaxSequenceNumberOfFrame(
        long ssrc, long timestamp, int sequenceNumber)
    {
        Map<Long, Integer> frameToMaxSequenceNumberMap
            = ssrcToFrameToMaxSequenceNumberMap
            .computeIfAbsent(ssrc, k -> new LRUCache<>(5));

        if (frameToMaxSequenceNumberMap.containsKey(timestamp))
        {
            int previousMaxSequenceNumber
                = getMaxSequenceNumberOfFrame(ssrc, timestamp);

            if (previousMaxSequenceNumber != -1
                && RtpUtils.isOlderSequenceNumberThan(
                    previousMaxSequenceNumber, sequenceNumber))
            {
                frameToMaxSequenceNumberMap.put(timestamp, sequenceNumber);
            }
        }
        else
        {
            frameToMaxSequenceNumberMap.put(timestamp, sequenceNumber);
        }
    }

    /**
     * @return true if this instance needs a keyframe, false otherwise.
     */
    @Override
    public boolean needsKeyframe()
    {
        if (vp8QualityFilter.needsKeyframe())
        {
            logger.debug(() -> "quality filter "
                    + vp8QualityFilter.hashCode()
                    + " says vp8 track needs keyframe");

            return true;
        }

        VP8Frame lastVP8Frame = lastVP8FrameProjection.getVP8Frame();
        if (lastVP8Frame == null)
        {
            logger.debug(() -> " track projection last frame is null, needs keyframe");
        }
        else if (lastVP8Frame.needsKeyframe())
        {
            logger.debug(() -> " last vp8 frame says we need keyframe");
        }
        boolean result = lastVP8Frame == null || lastVP8Frame.needsKeyframe();
        if (result)
        {
            logger.debug(() -> " vp8 track projection does need keyframe");
        }
        return result;
    }

    /**
     * Determines whether a packet should be accepted or not.
     *
     * @param rtpPacket the RTP packet to determine whether to project or not.
     * @param incomingIndex the quality index of the incoming RTP packet
     * @param targetIndex the target quality index we want to achieve
     * @return true if the packet should be accepted, false otherwise.
     */
    @Override
    public boolean accept(
        @NotNull VideoRtpPacket rtpPacket, int incomingIndex, int targetIndex)
    {
        VP8FrameProjection vp8FrameProjection
            = lookupVP8FrameProjection(rtpPacket);

        if (vp8FrameProjection == null)
        {
            vp8FrameProjection
                = createVP8FrameProjection(rtpPacket, incomingIndex, targetIndex);
        }

        return vp8FrameProjection != null
            && vp8FrameProjection.accept(rtpPacket);
    }

    /**
     * Rewrites the RTCP packet that is specified as an argument.
     *
     * @param rtcpSrPacket the RTCP packet to transform.
     * @return true if the RTCP packet is accepted, false otherwise, in which
     * case it needs to be dropped.
     */
    @Override
    public boolean rewriteRtcp(@NotNull RtcpSrPacket rtcpSrPacket)
    {
        VP8FrameProjection lastVP8FrameProjectionCopy = lastVP8FrameProjection;
        if (lastVP8FrameProjectionCopy.getVP8Frame() == null
            || rtcpSrPacket.getSenderSsrc() != lastVP8FrameProjectionCopy.getSSRC())
        {
            return false;
        }

        long srcTs = rtcpSrPacket.getSenderInfo().getRtpTimestamp();
        long delta = RtpUtils.getTimestampDiff(
            lastVP8FrameProjectionCopy.getTimestamp(),
            lastVP8FrameProjectionCopy.getVP8Frame().getTimestamp());

        long dstTs = RtpUtils.applyTimestampDelta(srcTs, delta);

        if (srcTs != dstTs)
        {
            rtcpSrPacket.getSenderInfo().setRtpTimestamp(dstTs);
        }

        return true;
    }

    @Override
    public RtpState getRtpState()
    {
        synchronized (this)
        {
            lastVP8FrameProjection.close();
        }

        return new RtpState(
            lastVP8FrameProjection.getSSRC(),
            lastVP8FrameProjection.maxSequenceNumber(),
            lastVP8FrameProjection.getTimestamp());
    }

    @Override
    public PayloadType getPayloadType()
    {
        return payloadType;
    }

    /**
     * Rewrites the RTP packet that is specified as an argument.
     *
     * @param rtpPacket the RTP packet to rewrite.
     * @param incomingPacketCache the packet cache to pull piggy-backed
     * packets from. Null is permissible, but in that case no packets will be
     * piggy backed.
     * @return any RTP packets to piggy-bac
     * @throws RewriteException if a VP8 frame projection is not found
     * for the RTP packet that is specified as a parameter.
     */
    @Override
    public VideoRtpPacket[] rewriteRtp(
        @NotNull VideoRtpPacket rtpPacket, PacketCache incomingPacketCache)
        throws RewriteException
    {
        if (!(rtpPacket instanceof Vp8Packet))
        {
            logger.info("Got a non-VP8 packet.");
            return null;
        }

        VP8FrameProjection vp8FrameProjection
            = lookupVP8FrameProjection(rtpPacket);
        if (vp8FrameProjection == null)
        {
            // This packet does not belong to a projected frame.
            throw new RewriteException();
        }

        Vp8Packet[] ret
            = vp8FrameProjection.rewriteRtp((Vp8Packet) rtpPacket, incomingPacketCache);

        return ret;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put(
                "class",
                VP8AdaptiveTrackProjectionContext.class.getSimpleName());

        debugState.put(
                "vp8FrameProjectionMapSize",
                vp8FrameProjectionMap.size());
        debugState.put("vp8QualityFilter", vp8QualityFilter.getDebugState());
        debugState.put("payloadType", payloadType.toString());

        return debugState;
    }
}
