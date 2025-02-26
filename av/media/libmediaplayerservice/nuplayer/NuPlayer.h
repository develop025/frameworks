/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef NU_PLAYER_H_

#define NU_PLAYER_H_

#include <media/MediaPlayerInterface.h>
#include <media/stagefright/foundation/AHandler.h>
#include <media/stagefright/NativeWindowWrapper.h>

namespace android {

struct ACodec;
struct MetaData;
struct NuPlayerDriver;

struct NuPlayer : public AHandler {
    NuPlayer();

    void setUID(uid_t uid);

    void setDriver(const wp<NuPlayerDriver> &driver);

    void setDataSourceAsync(const sp<IStreamSource> &source);

    void setDataSourceAsync(
            const char *url, const KeyedVector<String8, String8> *headers);

    void setDataSourceAsync(int fd, int64_t offset, int64_t length);

    void prepareAsync();

    void setVideoSurfaceTextureAsync(
            const sp<IGraphicBufferProducer> &bufferProducer);

    void setAudioSink(const sp<MediaPlayerBase::AudioSink> &sink);
    void start();

    void pause();
    void resume();

    // Will notify the driver through "notifyResetComplete" once finished.
    void resetAsync();

    // Will notify the driver through "notifySeekComplete" once finished.
    void seekToAsync(int64_t seekTimeUs);

    status_t setVideoScalingMode(int32_t mode);
    status_t getTrackInfo(Parcel* reply) const;
    status_t selectTrack(size_t trackIndex, bool select);

#ifndef ANDROID_DEFAULT_CODE
    void stop();
#ifdef MTK_CLEARMOTION_SUPPORT
	void enableClearMotion(int32_t enable);
#endif
    // mtk80902: ALPS00448589
    sp<MetaData> getMetaData() const; 
#endif

protected:
    virtual ~NuPlayer();

    virtual void onMessageReceived(const sp<AMessage> &msg);

public:
    struct NuPlayerStreamListener;
    struct Source;

private:
    struct Decoder;
    struct GenericSource;
    struct HTTPLiveSource;
    struct Renderer;
    struct RTSPSource;
    struct StreamingSource;
    struct Action;
    struct SeekAction;
    struct SetSurfaceAction;
    struct ShutdownDecoderAction;
    struct PostMessageAction;
    struct SimpleAction;

    enum {
        kWhatSetDataSource              = '=DaS',
        kWhatPrepare                    = 'prep',
        kWhatSetVideoNativeWindow       = '=NaW',
        kWhatSetAudioSink               = '=AuS',
        kWhatMoreDataQueued             = 'more',
        kWhatStart                      = 'strt',
        kWhatScanSources                = 'scan',
        kWhatVideoNotify                = 'vidN',
        kWhatAudioNotify                = 'audN',
        kWhatRendererNotify             = 'renN',
        kWhatReset                      = 'rset',
        kWhatSeek                       = 'seek',
        kWhatPause                      = 'paus',
        kWhatResume                     = 'rsme',
        kWhatPollDuration               = 'polD',
        kWhatSourceNotify               = 'srcN',
        kWhatGetTrackInfo               = 'gTrI',
        kWhatSelectTrack                = 'selT',
#ifndef ANDROID_DEFAULT_CODE
        kWhatStop			= 'stop'
#endif
    };

    wp<NuPlayerDriver> mDriver;
    bool mUIDValid;
    uid_t mUID;
    sp<Source> mSource;
    uint32_t mSourceFlags;
    sp<NativeWindowWrapper> mNativeWindow;
    sp<MediaPlayerBase::AudioSink> mAudioSink;
    sp<Decoder> mVideoDecoder;
    bool mVideoIsAVC;
    sp<Decoder> mAudioDecoder;
    sp<Renderer> mRenderer;

    List<sp<Action> > mDeferredActions;

    bool mAudioEOS;
    bool mVideoEOS;

    bool mScanSourcesPending;
    int32_t mScanSourcesGeneration;

    int32_t mPollDurationGeneration;

    enum FlushStatus {
        NONE,
        AWAITING_DISCONTINUITY,
        FLUSHING_DECODER,
        FLUSHING_DECODER_SHUTDOWN,
        SHUTTING_DOWN_DECODER,
        FLUSHED,
        SHUT_DOWN,
    };

    // Once the current flush is complete this indicates whether the
    // notion of time has changed.
    bool mTimeDiscontinuityPending;

    FlushStatus mFlushingAudio;
    FlushStatus mFlushingVideo;
#ifndef ANDROID_DEFAULT_CODE
    enum ConsumeStatus {
        Consume_NONE,
        Consume_AWAITING_DECODER_EOS,
        Consume_AWAITING_RENDER_EOS,
        Consume_AWAITING_DECODER_SHUTDOWN,
        Consume_DONE
    };
    ConsumeStatus mConsumingAudio;
    ConsumeStatus mConsumingVideo;
    bool mAudioAbsent;
    bool mVideoAbsent;
    bool mStopWhileConsume;
    bool mPauseWhileConsume;
#endif
    int64_t mSkipRenderingAudioUntilMediaTimeUs;
    int64_t mSkipRenderingVideoUntilMediaTimeUs;

    int64_t mVideoLateByUs;
    int64_t mNumFramesTotal, mNumFramesDropped;

    int32_t mVideoScalingMode;

    bool mStarted;

#ifndef ANDROID_DEFAULT_CODE
    int64_t mSeekTimeUs;
    int64_t mPositionUs;
    mutable Mutex mLock;
    int32_t mVideoWidth;
    int32_t mVideoHeight;
    bool isSeeking_l(){return mSeekTimeUs != -1;};
    bool isSeeking() {
        Mutex::Autolock autoLock(mLock);
        return isSeeking_l();
    };
    enum PrepareState {
        UNPREPARED,
        PREPARING,
        PREPARED,
        PREPARE_CANCELED
    };
    enum DataSourceType {
            SOURCE_Default,
            SOURCE_HttpLive,
            SOURCE_Local,
            SOURCE_Rtsp
    };
    enum PlayState {
        STOPPED,
        PLAYSENDING,
        PLAYING,
        PAUSING,
        PAUSED
    };

    PrepareState mPrepare;
    DataSourceType mDataSourceType;
    PlayState mPlayState;
    
    bool onScanSources();
    void finishPrepare(int err = OK);
    bool flushAfterSeekIfNecessary();
    void finishSeek();

// mtk80902: porting from AwesomePlayer, for ALPS00436540 now
// may fullfill later.
    enum {
        CACHE_UNDERRUN      = 0x80,
    };
    uint32_t mFlags;
#ifdef MTK_CLEARMOTION_SUPPORT
	volatile int32_t mEnClearMotion;
#endif
#ifdef MTK_POST_PROCESS_FRAMEWORK_SUPPORT
    volatile int32_t mEnPostProcessing;
#endif
#endif


    status_t instantiateDecoder(bool audio, sp<Decoder> *decoder);

    status_t feedDecoderInputData(bool audio, const sp<AMessage> &msg);
    void renderBuffer(bool audio, const sp<AMessage> &msg);

    void notifyListener(int msg, int ext1, int ext2, const Parcel *in = NULL);

    void finishFlushIfPossible();

    void flushDecoder(bool audio, bool needShutdown);
#ifndef ANDROID_DEFAULT_CODE
    static bool IsConsumingState(ConsumeStatus state);
    void consumeDecoder(bool audio);
    void finishConsumeIfPossible();
#endif
    static bool IsFlushingState(FlushStatus state, bool *needShutdown = NULL);

    void postScanSources();

    void schedulePollDuration();
    void cancelPollDuration();

    void processDeferredActions();

    void performSeek(int64_t seekTimeUs);
    void performDecoderFlush();
    void performDecoderShutdown(bool audio, bool video);
    void performReset();
    void performScanSources();
    void performSetSurface(const sp<NativeWindowWrapper> &wrapper);

    void onSourceNotify(const sp<AMessage> &msg);

    void queueDecoderShutdown(
            bool audio, bool video, const sp<AMessage> &reply);

    DISALLOW_EVIL_CONSTRUCTORS(NuPlayer);
};

}  // namespace android

#endif  // NU_PLAYER_H_
