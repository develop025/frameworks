/*
**
** Copyright 2008, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifndef ANDROID_MEDIAPLAYERSERVICE_H
#define ANDROID_MEDIAPLAYERSERVICE_H

#include <arpa/inet.h>

#include <utils/threads.h>
#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/String8.h>
#include <utils/Vector.h>

#include <media/MediaPlayerInterface.h>
#include <media/Metadata.h>
#include <media/stagefright/foundation/ABase.h>

#include <system/audio.h>

namespace android {

class AudioTrack;
class IMediaRecorder;
class IMediaMetadataRetriever;
class IOMX;
class IRemoteDisplay;
class IRemoteDisplayClient;
class MediaRecorderClient;
#ifndef ANDROID_DEFAULT_CODE
struct RemoteDisplay;
#endif

#define CALLBACK_ANTAGONIZER 0
#if CALLBACK_ANTAGONIZER
class Antagonizer {
public:
    Antagonizer(notify_callback_f cb, void* client);
    void start() { mActive = true; }
    void stop() { mActive = false; }
    void kill();
private:
    static const int interval;
    Antagonizer();
    static int callbackThread(void* cookie);
    Mutex               mLock;
    Condition           mCondition;
    bool                mExit;
    bool                mActive;
    void*               mClient;
    notify_callback_f   mCb;
};
#endif

class MediaPlayerService : public BnMediaPlayerService
{
    class Client;

    class AudioOutput : public MediaPlayerBase::AudioSink
    {
        class CallbackData;

     public:
                                AudioOutput(int sessionId, int uid);
        virtual                 ~AudioOutput();

        virtual bool            ready() const { return mTrack != 0; }
        virtual bool            realtime() const { return true; }
        virtual ssize_t         bufferSize() const;
        virtual ssize_t         frameCount() const;
        virtual ssize_t         channelCount() const;
        virtual ssize_t         frameSize() const;
        virtual uint32_t        latency() const;
        virtual float           msecsPerFrame() const;
        virtual status_t        getPosition(uint32_t *position) const;
        virtual status_t        getFramesWritten(uint32_t *frameswritten) const;
        virtual int             getSessionId() const;

        virtual status_t        open(
                uint32_t sampleRate, int channelCount, audio_channel_mask_t channelMask,
                audio_format_t format, int bufferCount,
                AudioCallback cb, void *cookie,
                audio_output_flags_t flags = AUDIO_OUTPUT_FLAG_NONE,
                const audio_offload_info_t *offloadInfo = NULL);

        virtual status_t        start();
        virtual ssize_t         write(const void* buffer, size_t size);
        virtual void            stop();
        virtual void            flush();
        virtual void            pause();
        virtual void            close();
                void            setAudioStreamType(audio_stream_type_t streamType) {
                                                                        mStreamType = streamType; }
        virtual audio_stream_type_t getAudioStreamType() const { return mStreamType; }

                void            setVolume(float left, float right);
        virtual status_t        setPlaybackRatePermille(int32_t ratePermille);
                status_t        setAuxEffectSendLevel(float level);
                status_t        attachAuxEffect(int effectId);
        virtual status_t        dump(int fd, const Vector<String16>& args) const;

        static bool             isOnEmulator();
        static int              getMinBufferCount();
                void            setNextOutput(const sp<AudioOutput>& nextOutput);
                void            switchToNextOutput();
        virtual bool            needsTrailingPadding() { return mNextOutput == NULL; }
        virtual status_t        setParameters(const String8& keyValuePairs);
        virtual String8         getParameters(const String8& keys);

    private:
        static void             setMinBufferCount();
        static void             CallbackWrapper(
                int event, void *me, void *info);
               void             deleteRecycledTrack();

        sp<AudioTrack>          mTrack;
        sp<AudioTrack>          mRecycledTrack;
        sp<AudioOutput>         mNextOutput;
        AudioCallback           mCallback;
        void *                  mCallbackCookie;
        CallbackData *          mCallbackData;
        uint64_t                mBytesWritten;
        audio_stream_type_t     mStreamType;
        float                   mLeftVolume;
        float                   mRightVolume;
        int32_t                 mPlaybackRatePermille;
        uint32_t                mSampleRateHz; // sample rate of the content, as set in open()
        float                   mMsecsPerFrame;
        int                     mSessionId;
        int                     mUid;
        float                   mSendLevel;
        int                     mAuxEffectId;
        static bool             mIsOnEmulator;
        static int              mMinBufferCount;  // 12 for emulator; otherwise 4
        audio_output_flags_t    mFlags;

        // CallbackData is what is passed to the AudioTrack as the "user" data.
        // We need to be able to target this to a different Output on the fly,
        // so we can't use the Output itself for this.
        class CallbackData {
        public:
            CallbackData(AudioOutput *cookie) {
                mData = cookie;
                mSwitching = false;
            }
            AudioOutput *   getOutput() { return mData;}
            void            setOutput(AudioOutput* newcookie) { mData = newcookie; }
            // lock/unlock are used by the callback before accessing the payload of this object
            void            lock() { mLock.lock(); }
            void            unlock() { mLock.unlock(); }
            // beginTrackSwitch/endTrackSwitch are used when this object is being handed over
            // to the next sink.
            void            beginTrackSwitch() { mLock.lock(); mSwitching = true; }
            void            endTrackSwitch() {
                if (mSwitching) {
                    mLock.unlock();
                }
                mSwitching = false;
            }
        private:
            AudioOutput *   mData;
            mutable Mutex   mLock;
            bool            mSwitching;
            DISALLOW_EVIL_CONSTRUCTORS(CallbackData);
        };

    }; // AudioOutput


    class AudioCache : public MediaPlayerBase::AudioSink
    {
    public:
                                AudioCache(const sp<IMemoryHeap>& heap);
        virtual                 ~AudioCache() {}

        virtual bool            ready() const { return (mChannelCount > 0) && (mHeap->getHeapID() > 0); }
        virtual bool            realtime() const { return false; }
        virtual ssize_t         bufferSize() const { return frameSize() * mFrameCount; }
        virtual ssize_t         frameCount() const { return mFrameCount; }
        virtual ssize_t         channelCount() const { return (ssize_t)mChannelCount; }
        virtual ssize_t         frameSize() const { return ssize_t(mChannelCount * ((mFormat == AUDIO_FORMAT_PCM_16_BIT)?sizeof(int16_t):sizeof(u_int8_t))); }
        virtual uint32_t        latency() const;
        virtual float           msecsPerFrame() const;
        virtual status_t        getPosition(uint32_t *position) const;
        virtual status_t        getFramesWritten(uint32_t *frameswritten) const;
        virtual int             getSessionId() const;

        virtual status_t        open(
                uint32_t sampleRate, int channelCount, audio_channel_mask_t channelMask,
                audio_format_t format, int bufferCount = 1,
                AudioCallback cb = NULL, void *cookie = NULL,
                audio_output_flags_t flags = AUDIO_OUTPUT_FLAG_NONE,
                const audio_offload_info_t *offloadInfo = NULL);

        virtual status_t        start();
        virtual ssize_t         write(const void* buffer, size_t size);
        virtual void            stop();
        virtual void            flush() {}
        virtual void            pause() {}
        virtual void            close() {}
                void            setAudioStreamType(audio_stream_type_t streamType) {}
                // stream type is not used for AudioCache
        virtual audio_stream_type_t getAudioStreamType() const { return AUDIO_STREAM_DEFAULT; }

                void            setVolume(float left, float right) {}
        virtual status_t        setPlaybackRatePermille(int32_t ratePermille) { return INVALID_OPERATION; }
                uint32_t        sampleRate() const { return mSampleRate; }
                audio_format_t  format() const { return mFormat; }
                size_t          size() const { return mSize; }
                status_t        wait();

                sp<IMemoryHeap> getHeap() const { return mHeap; }

        static  void            notify(void* cookie, int msg,
                                       int ext1, int ext2, const Parcel *obj);
        virtual status_t        dump(int fd, const Vector<String16>& args) const;

    private:
                                AudioCache();

        Mutex               mLock;
        Condition           mSignal;
        sp<IMemoryHeap>     mHeap;
        float               mMsecsPerFrame;
        uint16_t            mChannelCount;
        audio_format_t      mFormat;
        ssize_t             mFrameCount;
        uint32_t            mSampleRate;
        uint32_t            mSize;
        int                 mError;
        bool                mCommandComplete;

        sp<Thread>          mCallbackThread;
    }; // AudioCache

public:
    static  void                instantiate();

    // IMediaPlayerService interface
    virtual sp<IMediaRecorder>  createMediaRecorder();
    void    removeMediaRecorderClient(wp<MediaRecorderClient> client);
    virtual sp<IMediaMetadataRetriever> createMetadataRetriever();

    virtual sp<IMediaPlayer>    create(const sp<IMediaPlayerClient>& client, int audioSessionId);

    virtual status_t            decode(const char* url, uint32_t *pSampleRate, int* pNumChannels,
                                       audio_format_t* pFormat,
                                       const sp<IMemoryHeap>& heap, size_t *pSize);
    virtual status_t            decode(int fd, int64_t offset, int64_t length,
                                       uint32_t *pSampleRate, int* pNumChannels,
                                       audio_format_t* pFormat,
                                       const sp<IMemoryHeap>& heap, size_t *pSize);
    virtual sp<IOMX>            getOMX();
    virtual sp<ICrypto>         makeCrypto();
    virtual sp<IDrm>            makeDrm();
    virtual sp<IHDCP>           makeHDCP(bool createEncryptionModule);

    virtual sp<IRemoteDisplay> listenForRemoteDisplay(const sp<IRemoteDisplayClient>& client,
            const String8& iface);
#ifndef ANDROID_DEFAULT_CODE
    virtual status_t            enableRemoteDisplay(const char *iface);
    virtual sp<IRemoteDisplay> listenForRemoteDisplay(const sp<IRemoteDisplayClient>& client,
            const String8& iface, const uint32_t wfdFlags);
#ifdef MTK_WFD_SINK_SUPPORT    
    virtual sp<IRemoteDisplay> connectForRemoteDisplay(const sp<IRemoteDisplayClient>& client, 
            const String8& iface, const sp<IGraphicBufferProducer> &bufferProducer);
#endif
    virtual status_t            enableRemoteDisplay(const char *iface, const uint32_t wfdFlags);

    virtual status_t            sendGenericMsg(uint32_t cmd);
    virtual status_t            setBitrateControl(uint32_t level);  
    
#endif /* ANDROID_DEFAULT_CODE */
    virtual status_t            dump(int fd, const Vector<String16>& args);

    virtual status_t        updateProxyConfig(
            const char *host, int32_t port, const char *exclusionList);

            void                removeClient(wp<Client> client);

    // For battery usage tracking purpose
    struct BatteryUsageInfo {
        // how many streams are being played by one UID
        int     refCount;
        // a temp variable to store the duration(ms) of audio codecs
        // when we start a audio codec, we minus the system time from audioLastTime
        // when we pause it, we add the system time back to the audioLastTime
        // so after the pause, audioLastTime = pause time - start time
        // if multiple audio streams are played (or recorded), then audioLastTime
        // = the total playing time of all the streams
        int32_t audioLastTime;
        // when all the audio streams are being paused, we assign audioLastTime to
        // this variable, so this value could be provided to the battery app
        // in the next pullBatteryData call
        int32_t audioTotalTime;

        int32_t videoLastTime;
        int32_t videoTotalTime;
    };
    KeyedVector<int, BatteryUsageInfo>    mBatteryData;

    enum {
        SPEAKER,
        OTHER_AUDIO_DEVICE,
        SPEAKER_AND_OTHER,
        NUM_AUDIO_DEVICES
    };

    struct BatteryAudioFlingerUsageInfo {
        int refCount; // how many audio streams are being played
        int deviceOn[NUM_AUDIO_DEVICES]; // whether the device is currently used
        int32_t lastTime[NUM_AUDIO_DEVICES]; // in ms
        // totalTime[]: total time of audio output devices usage
        int32_t totalTime[NUM_AUDIO_DEVICES]; // in ms
    };

    // This varialble is used to record the usage of audio output device
    // for battery app
    BatteryAudioFlingerUsageInfo mBatteryAudio;

    // Collect info of the codec usage from media player and media recorder
    virtual void                addBatteryData(uint32_t params);
    // API for the Battery app to pull the data of codecs usage
    virtual status_t            pullBatteryData(Parcel* reply);
private:

    class Client : public BnMediaPlayer {
        // IMediaPlayer interface
        virtual void            disconnect();
        virtual status_t        setVideoSurfaceTexture(
                                        const sp<IGraphicBufferProducer>& bufferProducer);
        virtual status_t        prepareAsync();
        virtual status_t        start();
        virtual status_t        stop();
        virtual status_t        pause();
        virtual status_t        isPlaying(bool* state);
        virtual status_t        seekTo(int msec);
        virtual status_t        getCurrentPosition(int* msec);
        virtual status_t        getDuration(int* msec);
        virtual status_t        reset();
        virtual status_t        setAudioStreamType(audio_stream_type_t type);
        virtual status_t        setLooping(int loop);
        virtual status_t        setVolume(float leftVolume, float rightVolume);
        virtual status_t        invoke(const Parcel& request, Parcel *reply);
        virtual status_t        setMetadataFilter(const Parcel& filter);
        virtual status_t        getMetadata(bool update_only,
                                            bool apply_filter,
                                            Parcel *reply);
        virtual status_t        setAuxEffectSendLevel(float level);
        virtual status_t        attachAuxEffect(int effectId);
        virtual status_t        setParameter(int key, const Parcel &request);
        virtual status_t        getParameter(int key, Parcel *reply);
        virtual status_t        setRetransmitEndpoint(const struct sockaddr_in* endpoint);
        virtual status_t        getRetransmitEndpoint(struct sockaddr_in* endpoint);
        virtual status_t        setNextPlayer(const sp<IMediaPlayer>& player);

        sp<MediaPlayerBase>     createPlayer(player_type playerType);

        virtual status_t        setDataSource(
                        const char *url,
                        const KeyedVector<String8, String8> *headers);

        virtual status_t        setDataSource(int fd, int64_t offset, int64_t length);

        virtual status_t        setDataSource(const sp<IStreamSource> &source);

        sp<MediaPlayerBase>     setDataSource_pre(player_type playerType);
        void                    setDataSource_post(const sp<MediaPlayerBase>& p,
                                                   status_t status);

        static  void            notify(void* cookie, int msg,
                                       int ext1, int ext2, const Parcel *obj);

                pid_t           pid() const { return mPid; }
        virtual status_t        dump(int fd, const Vector<String16>& args) const;

                int             getAudioSessionId() { return mAudioSessionId; }

    private:
        friend class MediaPlayerService;
#ifndef ANDROID_DEFAULT_CODE
#ifdef NOTIFYSENDER_ENABLE
		    friend class NotifySender;
#endif
#endif
                                Client( const sp<MediaPlayerService>& service,
                                        pid_t pid,
                                        int32_t connId,
                                        const sp<IMediaPlayerClient>& client,
                                        int audioSessionId,
                                        uid_t uid);
                                Client();
        virtual                 ~Client();

                void            deletePlayer();

        sp<MediaPlayerBase>     getPlayer() const { Mutex::Autolock lock(mLock); return mPlayer; }



        // @param type Of the metadata to be tested.
        // @return true if the metadata should be dropped according to
        //              the filters.
        bool shouldDropMetadata(media::Metadata::Type type) const;

        // Add a new element to the set of metadata updated. Noop if
        // the element exists already.
        // @param type Of the metadata to be recorded.
        void addNewMetadataUpdate(media::Metadata::Type type);

        // Disconnect from the currently connected ANativeWindow.
        void disconnectNativeWindow();

        mutable     Mutex                       mLock;
                    sp<MediaPlayerBase>         mPlayer;
                    sp<MediaPlayerService>      mService;
                    sp<IMediaPlayerClient>      mClient;
                    sp<AudioOutput>             mAudioOutput;
                    pid_t                       mPid;
                    status_t                    mStatus;
                    bool                        mLoop;
                    int32_t                     mConnId;
                    int                         mAudioSessionId;
                    uid_t                       mUID;
                    sp<ANativeWindow>           mConnectedWindow;
                    sp<IBinder>                 mConnectedWindowBinder;
                    struct sockaddr_in          mRetransmitEndpoint;
                    bool                        mRetransmitEndpointValid;
                    sp<Client>                  mNextClient;

        // Metadata filters.
        media::Metadata::Filter mMetadataAllow;  // protected by mLock
        media::Metadata::Filter mMetadataDrop;  // protected by mLock

        // Metadata updated. For each MEDIA_INFO_METADATA_UPDATE
        // notification we try to update mMetadataUpdated which is a
        // set: no duplicate.
        // getMetadata clears this set.
        media::Metadata::Filter mMetadataUpdated;  // protected by mLock

#if CALLBACK_ANTAGONIZER
                    Antagonizer*                mAntagonizer;
#endif
    }; // Client
    
#ifndef ANDROID_DEFAULT_CODE
#ifdef NOTIFYSENDER_ENABLE
        friend class NotifySender;
#endif
#endif

// ----------------------------------------------------------------------------

                            MediaPlayerService();
    virtual                 ~MediaPlayerService();

    mutable     Mutex                       mLock;
                SortedVector< wp<Client> >  mClients;
                SortedVector< wp<MediaRecorderClient> > mMediaRecorderClients;
                int32_t                     mNextConnId;
                sp<IOMX>                    mOMX;
                sp<ICrypto>                 mCrypto;
#ifndef ANDROID_DEFAULT_CODE
                sp<RemoteDisplay>           mRemoteDisplay;
#endif
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_MEDIAPLAYERSERVICE_H
