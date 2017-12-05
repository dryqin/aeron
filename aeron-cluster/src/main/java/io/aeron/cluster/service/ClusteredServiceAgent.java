/*
 * Copyright 2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster.service;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.RecordingEventsAdapter;
import io.aeron.archive.client.RecordingEventsListener;
import io.aeron.cluster.codecs.*;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentInvoker;

public class ClusteredServiceAgent implements
    Agent, ControlledFragmentHandler, Cluster, RecordingEventsListener, AvailableImageHandler, UnavailableImageHandler
{
    /**
     * Length of the session header that will be precede application protocol message.
     */
    public static final int SESSION_HEADER_LENGTH =
        MessageHeaderDecoder.ENCODED_LENGTH + SessionHeaderDecoder.BLOCK_LENGTH;

    private static final int MAX_SEND_ATTEMPTS = 3;
    private static final int FRAGMENT_LIMIT = 10;
    private static final int INITIAL_BUFFER_LENGTH = 4096;

    private long timestampMs;
    private long archivedPosition;
    private final long recordingId;
    private final boolean shouldCloseResources;
    private final boolean useAeronAgentInvoker;
    private final Aeron aeron;
    private final AgentInvoker aeronAgentInvoker;
    private final ClusteredService service;
    private final Subscription logSubscription;
    private final ExclusivePublication timerPublication;
    private final ControlledFragmentAssembler fragmentAssembler =
        new ControlledFragmentAssembler(this, INITIAL_BUFFER_LENGTH, true);
    private final MessageHeaderDecoder messageHeaderDecoder = new MessageHeaderDecoder();
    private final SessionOpenEventDecoder openEventDecoder = new SessionOpenEventDecoder();
    private final SessionCloseEventDecoder closeEventDecoder = new SessionCloseEventDecoder();
    private final SessionHeaderDecoder sessionHeaderDecoder = new SessionHeaderDecoder();
    private final TimerEventDecoder timerEventDecoder = new TimerEventDecoder();
    private final BufferClaim bufferClaim = new BufferClaim();
    private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
    private final ScheduleTimerRequestEncoder scheduleTimerRequestEncoder = new ScheduleTimerRequestEncoder();
    private final CancelTimerRequestEncoder cancelTimerRequestEncoder = new CancelTimerRequestEncoder();

    private final Long2ObjectHashMap<ClientSession> sessionByIdMap = new Long2ObjectHashMap<>();

    private final RecordingEventsAdapter recordingEventsAdapter;
    private final AeronArchive aeronArchive;
    private Image logImage;

    public ClusteredServiceAgent(final ClusteredServiceContainer.Context ctx)
    {
        aeron = ctx.aeron();
        shouldCloseResources = ctx.ownsAeronClient();
        useAeronAgentInvoker = aeron.context().useConductorAgentInvoker();
        aeronAgentInvoker = aeron.conductorAgentInvoker();
        service = ctx.clusteredService();
        logSubscription = aeron.addSubscription(ctx.logChannel(), ctx.logStreamId(), this, this);
        timerPublication = aeron.addExclusivePublication(ctx.timerChannel(), ctx.timerStreamId());

        // TODO: grab context from container context
        aeronArchive = AeronArchive.connect(new AeronArchive.Context());

        final MutableLong archiveStartPosition = new MutableLong();
        final MutableLong archiveRecordingId = new MutableLong();

        final int numRecordings = aeronArchive.listRecordingsForUri(
            0,
            1,
            ctx.logChannel(),
            ctx.logStreamId(),
            (
                controlSessionId,
                correlationId,
                recordingId,
                startTimestamp,
                stopTimestamp,
                startPosition,
                stopPosition,
                initialTermId,
                segmentFileLength,
                termBufferLength,
                mtuLength,
                sessionId,
                streamId,
                strippedChannel,
                originalChannel,
                sourceIdentity
            ) ->
            {
                archiveRecordingId.value = recordingId;
                archiveStartPosition.value = startPosition;
            });

        recordingId = archiveRecordingId.value;
        archivedPosition = archiveStartPosition.value;

        final Subscription recordingEventsSubscription =
            aeron.addSubscription(
                AeronArchive.Configuration.recordingEventsChannel(),
                AeronArchive.Configuration.recordingEventsStreamId());

        recordingEventsAdapter = new RecordingEventsAdapter(this, recordingEventsSubscription, FRAGMENT_LIMIT);
    }

    public void onStart()
    {
        service.onStart(this);
    }

    public void onClose()
    {
        if (shouldCloseResources)
        {
            CloseHelper.close(logSubscription);
            CloseHelper.close(timerPublication);

            for (final ClientSession session : sessionByIdMap.values())
            {
                CloseHelper.close(session.responsePublication());
            }
        }
    }

    public int doWork() throws Exception
    {
        int workCount = 0;

        if (useAeronAgentInvoker)
        {
            workCount += aeronAgentInvoker.invoke();
        }

        workCount += recordingEventsAdapter.poll();

        if (null != logImage)
        {
            workCount += logImage.boundedControlledPoll(fragmentAssembler, archivedPosition, FRAGMENT_LIMIT);
        }

        return workCount;
    }

    public String roleName()
    {
        return "clustered-service";
    }

    public ControlledFragmentHandler.Action onFragment(
        final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        messageHeaderDecoder.wrap(buffer, offset);

        final int templateId = messageHeaderDecoder.templateId();
        switch (templateId)
        {
            case SessionHeaderDecoder.TEMPLATE_ID:
            {
                sessionHeaderDecoder.wrap(
                    buffer,
                    offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    messageHeaderDecoder.blockLength(),
                    messageHeaderDecoder.version());

                timestampMs = sessionHeaderDecoder.timestamp();
                service.onSessionMessage(
                    sessionHeaderDecoder.clusterSessionId(),
                    sessionHeaderDecoder.correlationId(),
                    timestampMs,
                    buffer,
                    offset + SESSION_HEADER_LENGTH,
                    length - SESSION_HEADER_LENGTH,
                    header);

                break;
            }

            case TimerEventDecoder.TEMPLATE_ID:
            {
                timerEventDecoder.wrap(
                    buffer,
                    offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    messageHeaderDecoder.blockLength(),
                    messageHeaderDecoder.version());

                timestampMs = timerEventDecoder.timestamp();
                service.onTimerEvent(timerEventDecoder.correlationId(), timestampMs);
                break;
            }

            case SessionOpenEventDecoder.TEMPLATE_ID:
            {
                openEventDecoder.wrap(
                    buffer,
                    offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    messageHeaderDecoder.blockLength(),
                    messageHeaderDecoder.version());

                final long sessionId = openEventDecoder.clusterSessionId();
                final ClientSession session = new ClientSession(
                    sessionId,
                    aeron.addExclusivePublication(
                        openEventDecoder.responseChannel(),
                        openEventDecoder.responseStreamId()),
                        this);

                sessionByIdMap.put(sessionId, session);
                timestampMs = openEventDecoder.timestamp();
                service.onSessionOpen(session, timestampMs);
                break;
            }

            case SessionCloseEventDecoder.TEMPLATE_ID:
            {
                closeEventDecoder.wrap(
                    buffer,
                    offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    messageHeaderDecoder.blockLength(),
                    messageHeaderDecoder.version());

                final ClientSession session = sessionByIdMap.remove(closeEventDecoder.clusterSessionId());
                if (null != session)
                {
                    timestampMs = closeEventDecoder.timestamp();
                    session.responsePublication().close();
                    service.onSessionClose(session, timestampMs, closeEventDecoder.closeReason());
                }
                break;
            }
        }

        return Action.CONTINUE;
    }

    public ClientSession getClientSession(final long clusterSessionId)
    {
        return sessionByIdMap.get(clusterSessionId);
    }

    public long timeMs()
    {
        return timestampMs;
    }

    public void scheduleTimer(final long correlationId, final long deadlineMs)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + ScheduleTimerRequestEncoder.BLOCK_LENGTH;

        int attempts = MAX_SEND_ATTEMPTS;
        do
        {
            if (timerPublication.tryClaim(length, bufferClaim) > 0)
            {
                scheduleTimerRequestEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .correlationId(correlationId)
                    .deadline(deadlineMs);

                bufferClaim.commit();

                return;
            }

            Thread.yield();
        }
        while (--attempts > 0);

        throw new IllegalStateException("Failed to schedule timer");
    }

    public void cancelTimer(final long correlationId)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + CancelTimerRequestEncoder.BLOCK_LENGTH;

        int attempts = MAX_SEND_ATTEMPTS;
        do
        {
            if (timerPublication.tryClaim(length, bufferClaim) > 0)
            {
                cancelTimerRequestEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .correlationId(correlationId);

                bufferClaim.commit();

                return;
            }

            Thread.yield();
        }
        while (--attempts > 0);

        throw new IllegalStateException("Failed to schedule timer");
    }

    public void onStart(
        final long recordingId,
        final long startPosition,
        final int sessionId,
        final int streamId,
        final String channel,
        final String sourceIdentity)
    {
        //System.out.println("Recording started for id: " + recordingId);
    }

    public void onProgress(final long recordingId, final long startPosition, final long position)
    {
        if (recordingId == this.recordingId)
        {
            archivedPosition = position;
        }
    }

    public void onStop(final long recordingId, final long startPosition, final long stopPosition)
    {
        //System.out.println("onStop");
    }

    public void onAvailableImage(final Image image)
    {
        // TODO: make sessionId specific?
        logImage = image;
    }

    public void onUnavailableImage(final Image image)
    {
        logImage = null;
    }
}
