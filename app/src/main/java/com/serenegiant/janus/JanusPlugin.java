package com.serenegiant.janus;

import android.os.Build;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.serenegiant.janus.request.Attach;
import com.serenegiant.janus.request.Configure;
import com.serenegiant.janus.request.Detach;
import com.serenegiant.janus.request.Join;
import com.serenegiant.janus.request.JsepSdp;
import com.serenegiant.janus.request.Message;
import com.serenegiant.janus.request.Start;
import com.serenegiant.janus.request.Trickle;
import com.serenegiant.janus.request.TrickleCompleted;
import com.serenegiant.janus.response.EventRoom;
import com.serenegiant.janus.response.Plugin;
import com.serenegiant.janus.response.PublisherInfo;
import com.serenegiant.janus.response.Session;

import org.appspot.apprtc.PeerConnectionParameters;
import org.appspot.apprtc.RtcEventLog;
import org.appspot.apprtc.util.SdpUtils;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static org.appspot.apprtc.AppRTCConst.AUDIO_CODEC_ISAC;
import static org.appspot.apprtc.AppRTCConst.AUDIO_CODEC_OPUS;

/*package*/ abstract class JanusPlugin {
	private static final boolean DEBUG = true;	// set false on production
	
	/**
	 * callback interface for JanusPlugin
	 */
	public interface JanusPluginCallback {
		public void onAttach(@NonNull final JanusPlugin plugin);
		public void onJoin(@NonNull final JanusPlugin plugin, final EventRoom room);
		public void onDetach(@NonNull final JanusPlugin plugin);
		public void onLeave(@NonNull final JanusPlugin plugin, @NonNull final BigInteger pluginId);
		public void onRemoteIceCandidate(@NonNull final JanusPlugin plugin,
			final IceCandidate remoteCandidate);
		/**
		 * Callback fired once local SDP is created and set.
		 */
		public void onLocalDescription(@NonNull final JanusPlugin plugin, final SessionDescription sdp);

		@NonNull
		public PeerConnectionParameters getPeerConnectionParameters(@NonNull final JanusPlugin plugin);
		public boolean isVideoCallEnabled(@NonNull final JanusPlugin plugin);

		public void createSubscriber(@NonNull final JanusPlugin plugin,
			@NonNull final BigInteger feederId);

		/**
		 * リモート側のSessionDescriptionを受信した時
		 * これを呼び出すと通話中の状態になる
		 * @param plugin
		 * @param sdp
		 */
		public void onRemoteDescription(@NonNull final JanusPlugin plugin,
			final SessionDescription sdp);

		public void onError(@NonNull final JanusPlugin plugin,
			@NonNull final Throwable t);
	}
	
	private static enum RoomState {
		UNINITIALIZED,
		ATTACHED,
		CONNECTED,
		CLOSED,
		ERROR }

	protected final String TAG = "JanusPlugin:" + getClass().getSimpleName();

	@NonNull
	private final MediaConstraints sdpMediaConstraints;
	private PeerConnection peerConnection;
	/** Enable org.appspot.apprtc.RtcEventLog. */
	@Nullable
	RtcEventLog rtcEventLog;
	@Nullable
	private DataChannel dataChannel;
	/**
	 * Queued remote ICE candidates are consumed only after both local and
	 * remote descriptions are set. Similarly local ICE candidates are sent to
	 * remote peer after both local and remote description are set.
	 */
	@android.support.annotation.Nullable
	private List<IceCandidate> queuedRemoteCandidates;

	@NonNull
	protected final VideoRoom mVideoRoom;
	@NonNull
	protected final Session mSession;
	@NonNull
	protected final JanusPluginCallback mCallback;
	protected final ExecutorService executor = JanusRTCClient.executor;
	protected final List<Call<?>> mCurrentCalls = new ArrayList<>();
	protected RoomState mRoomState = RoomState.UNINITIALIZED;
	protected Plugin mPlugin;
	protected Room mRoom;
	protected SessionDescription mLocalSdp;
	protected SessionDescription mRemoteSdp;
	protected boolean isInitiator;
	protected boolean isError;
	private final boolean preferIsac;
	
	/**
	 * constructor
	 * @param session
	 * @param callback
	 */
	public JanusPlugin(@NonNull VideoRoom videoRoom,
		@NonNull final Session session,
		@NonNull final JanusPluginCallback callback,
		@NonNull final MediaConstraints sdpMediaConstraints,
		@NonNull final PeerConnection peerConnection,
		@Nullable final DataChannel dataChannel,
		@Nullable final RtcEventLog rtcEventLog) {
		
		this.mVideoRoom = videoRoom;
		this.mSession = session;
		this.mCallback = callback;
		this.sdpMediaConstraints = sdpMediaConstraints;
		this.peerConnection = peerConnection;
		this.dataChannel = dataChannel;
		this.rtcEventLog = rtcEventLog;
		
		final PeerConnectionParameters peerConnectionParameters
			= callback.getPeerConnectionParameters(this);
		// Check if ISAC is used by default.
		preferIsac = peerConnectionParameters.audioCodec != null
			&& peerConnectionParameters.audioCodec.equals(AUDIO_CODEC_ISAC);
		queuedRemoteCandidates = new ArrayList<>();
	}
	
	@Override
	protected void finalize() throws Throwable {
		try {
			detach();
		} finally {
			super.finalize();
		}
	}
	
	BigInteger id() {
		return mPlugin != null ? mPlugin.id() : null;
	}
	
	public void createOffer() {
		if (DEBUG) Log.v(TAG, "createOffer:");
		executor.execute(() -> {
			if (peerConnection != null && !isError) {
				if (DEBUG) Log.d(TAG, "PC Create OFFER");
				isInitiator = true;
				peerConnection.createOffer(mSdpObserver, sdpMediaConstraints);
			}
		});
	}
	
	public void createAnswer() {
		if (DEBUG) Log.v(TAG, "createAnswer:");
		executor.execute(() -> {
			if (peerConnection != null && !isError) {
				if (DEBUG) Log.d(TAG, "PC create ANSWER");
				isInitiator = false;
				peerConnection.createAnswer(mSdpObserver, sdpMediaConstraints);
			}
		});
	}
	
	private void drainCandidates() {
		if (DEBUG) Log.v(TAG, "drainCandidates:");
		if (queuedRemoteCandidates != null) {
			if (DEBUG) Log.d(TAG, "Add " + queuedRemoteCandidates.size() + " remote candidates");
			for (IceCandidate candidate : queuedRemoteCandidates) {
				peerConnection.addIceCandidate(candidate);
			}
			queuedRemoteCandidates = null;
		}
	}

	public void addRemoteIceCandidate(final IceCandidate candidate) {
		if (DEBUG) Log.v(TAG, "addRemoteIceCandidate:");
		executor.execute(() -> {
			if (peerConnection != null && !isError) {
				if (queuedRemoteCandidates != null) {
					queuedRemoteCandidates.add(candidate);
				} else {
					peerConnection.addIceCandidate(candidate);
				}
			}
		});
	}
	
	public void removeRemoteIceCandidates(final IceCandidate[] candidates) {
		if (DEBUG) Log.v(TAG, "removeRemoteIceCandidates:");
		executor.execute(() -> {
			if (peerConnection == null || isError) {
				return;
			}
			// Drain the queued remote candidates if there is any so that
			// they are processed in the proper order.
			drainCandidates();
			peerConnection.removeIceCandidates(candidates);
		});
	}

	public void setRemoteDescription(final SessionDescription sdp) {
		executor.execute(() -> {
			if (peerConnection == null || isError) {
				return;
			}
			final PeerConnectionParameters peerConnectionParameters
				= mCallback.getPeerConnectionParameters(JanusPlugin.this);
			String sdpDescription = sdp.description;
			if (preferIsac) {
				sdpDescription = SdpUtils.preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true);
			}
			if (mCallback.isVideoCallEnabled(JanusPlugin.this)) {
				sdpDescription =
					SdpUtils.preferCodec(sdpDescription,
						peerConnectionParameters.getSdpVideoCodecName(), false);
			}
			if (peerConnectionParameters.audioStartBitrate > 0) {
				sdpDescription = SdpUtils.setStartBitrate(
					AUDIO_CODEC_OPUS, false, sdpDescription, peerConnectionParameters.audioStartBitrate);
			}
			if (DEBUG) Log.d(TAG, "Set remote SDP.");
			final SessionDescription sdpRemote = new SessionDescription(sdp.type, sdpDescription);
			peerConnection.setRemoteDescription(mSdpObserver, sdpRemote);
		});
	}

	@NonNull
	protected abstract String getPType();

	protected BigInteger getFeedId() {
		return null;
	}

	/**
	 * attach to VideoRoom plugin
	 */
	public void attach() {
		if (DEBUG) Log.v(TAG, "attach:");
		final Attach attach = new Attach(mSession,
			"janus.plugin.videoroom",
			null);
		final Call<Plugin> call = mVideoRoom.attach(mSession.id(), attach);
		addCall(call);
		call.enqueue(new Callback<Plugin>() {
			@Override
			public void onResponse(@NonNull final Call<Plugin> call,
				@NonNull final Response<Plugin> response) {

				if (response.isSuccessful() && (response.body() != null)) {
					removeCall(call);
					final Plugin plugin = response.body();
					if ("success".equals(plugin.janus)) {
						mPlugin = plugin;
						mRoom = new Room(mSession, mPlugin);
						mRoomState = RoomState.ATTACHED;
						// プラグインにアタッチできた＼(^o^)／
						if (DEBUG) Log.v(TAG, "attach:success");
						mCallback.onAttach(JanusPlugin.this);
						// ルームへjoin
						executor.execute(() -> {
							join();
						});
					} else {
						reportError(new RuntimeException("unexpected response:" + response));
					}
				} else {
					reportError(new RuntimeException("unexpected response:" + response));
				}
			}
			
			@Override
			public void onFailure(@NonNull final Call<Plugin> call,
				@NonNull final Throwable t) {

				reportError(t);
			}
		});
	}
	
	/**
	 * join to Room
	 * @throws IOException
	 */
	public void join() {
		if (DEBUG) Log.v(TAG, "join:");
		final Message message = new Message(mRoom,
			new Join(1234/*FIXME*/, getPType(), Build.MODEL, getFeedId()),
			mTransactionCallback);
		if (DEBUG) Log.v(TAG, "join:" + message);
		final Call<EventRoom> call = mVideoRoom.join(mSession.id(), mPlugin.id(), message);
		addCall(call);
		try {
			final Response<EventRoom> response = call.execute();
			if (response.isSuccessful() && (response.body() != null)) {
				removeCall(call);
				final EventRoom join = response.body();
				if ("event".equals(join.janus)) {
					if (DEBUG) Log.v(TAG, "多分ここにはこない, ackが返ってくるはず");
					handlePluginEventJoined(message.transaction, join);
				} else if (!"ack".equals(join.janus)
					&& !"keepalive".equals(join.janus)) {

					throw new RuntimeException("unexpected response:" + response);
				}
				// 実際の応答はlong pollで待機
			} else {
				throw new RuntimeException("unexpected response:" + response);
			}
		} catch (final Exception e) {
			TransactionManager.removeTransaction(message.transaction);
			cancelCall();
			detach();
			reportError(e);
		}
	}
	
	/**
	 * detach from VideoRoom plugin
	 */
	public void detach() {
		if ((mRoomState == RoomState.CONNECTED)
			|| (mRoomState == RoomState.ATTACHED)
			|| (mPlugin != null)
			|| (peerConnection != null)) {

			if (DEBUG) Log.v(TAG, "detach:");
			cancelCall();
			final Call<Void> call = mVideoRoom.detach(mSession.id(), mPlugin.id(),
				new Detach(mSession, mTransactionCallback));
			addCall(call);
			try {
				call.execute();
			} catch (final IOException e) {
				if (DEBUG) Log.w(TAG, e);
			}
			removeCall(call);
			if (DEBUG) Log.d(TAG, "Closing peer connection.");
			mRoomState = RoomState.CLOSED;
			mRoom = null;
			mPlugin = null;
			if (dataChannel != null) {
				dataChannel.dispose();
				dataChannel = null;
			}
			if (rtcEventLog != null) {
				// RtcEventLog should stop before the peer connection is disposed.
				rtcEventLog.stop();
				rtcEventLog = null;
			}
			if (peerConnection != null) {
				peerConnection.dispose();
				peerConnection = null;
			}
		}
	}

	public void sendOfferSdp(final SessionDescription sdp, final boolean isLoopback) {
		if (DEBUG) Log.v(TAG, "sendOfferSdp:");
		if (mRoomState != RoomState.CONNECTED) {
			reportError(new RuntimeException("Sending offer SDP in non connected state."));
			return;
		}
		final Call<EventRoom> call = mVideoRoom.offer(
			mSession.id(),
			mPlugin.id(),
			new Message(mRoom,
				new Configure(true, true),
				new JsepSdp("offer", sdp.description),
				mTransactionCallback)
		);
		addCall(call);
		try {
			final Response<EventRoom> response = call.execute();
			if (DEBUG) Log.v(TAG, "sendOfferSdp:response=" + response
				+ "\n" + response.body());
			if (response.isSuccessful() && (response.body() != null)) {
				removeCall(call);
				final EventRoom offer = response.body();
				if ("event".equals(offer.janus)) {
					if (DEBUG) Log.v(TAG, "多分ここにはこない, ackが返ってくるはず");
					final SessionDescription answerSdp
						= new SessionDescription(
						SessionDescription.Type.fromCanonicalForm("answer"),
						offer.jsep.sdp);
					mCallback.onRemoteDescription(this, answerSdp);
				} else if (!"ack".equals(offer.janus)
					&& !"keepalive".equals(offer.janus)) {
					throw new RuntimeException("unexpected response " + response);
				}
				// 実際の待機はlong pollで行う
			} else {
				throw new RuntimeException("failed to send offer sdp");
			}
			if (isLoopback) {
				// In loopback mode rename this offer to answer and route it back.
				mCallback.onRemoteDescription(this, new SessionDescription(
					SessionDescription.Type.fromCanonicalForm("answer"),
					sdp.description));
			}
		} catch (final Exception e) {
			cancelCall();
			reportError(e);
		}
	}
	
	public void sendAnswerSdp(final SessionDescription sdp, final boolean isLoopback) {
		if (DEBUG) Log.v(TAG, "sendAnswerSdpInternal:");
		if (isLoopback) {
			Log.e(TAG, "Sending answer in loopback mode.");
			return;
		}
		final Call<ResponseBody> call = mVideoRoom.send(
			mSession.id(),
			mPlugin.id(),
			new Message(mRoom,
				new Start(1234),
				new JsepSdp("answer", sdp.description),
				mTransactionCallback)
		);
		addCall(call);
		try {
			final Response<ResponseBody> response = call.execute();
			if (DEBUG) Log.v(TAG, "sendAnswerSdpInternal:response=" + response
				+ "\n" + response.body());
			removeCall(call);
		} catch (final IOException e) {
			cancelCall();
			reportError(e);
		}
	}

	public void sendLocalIceCandidate(final IceCandidate candidate, final boolean isLoopback) {
		if (DEBUG) Log.v(TAG, "sendLocalIceCandidate:");
		final Call<EventRoom> call;
		if (candidate != null) {
			call = mVideoRoom.trickle(
				mSession.id(),
				mPlugin.id(),
				new Trickle(mRoom, candidate, mTransactionCallback)
			);
		} else {
			call = mVideoRoom.trickleCompleted(
				mSession.id(),
				mPlugin.id(),
				new TrickleCompleted(mRoom, mTransactionCallback)
			);
		}
		addCall(call);
		try {
			final Response<EventRoom> response = call.execute();
//				if (DEBUG) Log.v(TAG, "sendLocalIceCandidate:response=" + response
//					+ "\n" + response.body());
			if (response.isSuccessful() && (response.body() != null)) {
				removeCall(call);
				final EventRoom join = response.body();
				if ("event".equals(join.janus)) {
					if (DEBUG) Log.v(TAG, "多分ここにはこない, ackが返ってくるはず");
//					// FIXME 正常に処理できた…Roomの情報を更新する
//					IceCandidate remoteCandidate = null;
//					// FIXME removeCandidateを生成する
//					if (remoteCandidate != null) {
//						mCallback.onRemoteIceCandidate(this, remoteCandidate);
//					} else {
//						// FIXME remoteCandidateがなかった時
//					}
				} else if (!"ack".equals(join.janus)
					&& !"keepalive".equals(join.janus)) {

					throw new RuntimeException("unexpected response " + response);
				}
				// 実際の待機はlong pollで行う
			} else {
				throw new RuntimeException("unexpected response " + response);
			}
			if ((candidate != null) && isLoopback) {
				mCallback.onRemoteIceCandidate(this, candidate);
			}
		} catch (final IOException e) {
			cancelCall();
			detach();
			reportError(e);
		}
	}

//--------------------------------------------------------------------------------
// Long pollによるメッセージ受信時の処理関係
	/**
	 * TransactionManagerからのコールバックインターフェースの実装
	 */
	protected final TransactionManager.TransactionCallback
		mTransactionCallback = new TransactionManager.TransactionCallback() {
	
		/**
		 * usually this is called from from long poll
		 * 実際の処理は上位クラスの#onReceivedへ移譲
		 * @param body
		 * @return
		 */
		@Override
		public boolean onReceived(@NonNull final String transaction,
			 final JSONObject body) {

			return JanusPlugin.this.onReceived(transaction, body);
		}
	};
	
	/**
	 * TransactionManagerからのコールバックの実際の処理
	 * @param body
	 * @return
	 */
	protected boolean onReceived(@NonNull final String transaction,
		final JSONObject body) {

		if (DEBUG) Log.v(TAG, "onReceived:");
		final String janus = body.optString("janus");
		boolean handled = false;
		if (!TextUtils.isEmpty(janus)) {
			switch (janus) {
			case "ack":
				// do nothing
				return true;
			case "keepalive":
				// サーバー側がタイムアウト(30秒？)した時は{"janus": "keepalive"}が来る
				// do nothing
				return true;
			case "event":
				// プラグインイベント
				handled = handlePluginEvent(transaction, body);
				break;
			case "media":
			case "webrtcup":
			case "slowlink":
			case "hangup":
				// event for WebRTC
				handled = handleWebRTCEvent(transaction, body);
				break;
			case "error":
				reportError(new RuntimeException("error response\n" + body));
				return true;
			default:
				Log.d(TAG, "handleLongPoll:unknown event\n" + body);
				break;
			}
		} else {
			Log.d(TAG, "handleLongPoll:unexpected response\n" + body);
		}
		return handled;	// true: handled
	}

	/**
	 * プラグイン向けのイベントメッセージの処理
	 * @param body
	 * @return
	 */
	protected boolean handlePluginEvent(@NonNull final String transaction,
		final JSONObject body) {

		if (DEBUG) Log.v(TAG, "handlePluginEvent:");
		final Gson gson = new Gson();
		final EventRoom event = gson.fromJson(body.toString(), EventRoom.class);
		// XXX このsenderはPublisherとして接続したときのVideoRoomプラグインのidらしい
		final BigInteger sender = event.sender;
		final String eventType = (event.plugindata != null) && (event.plugindata.data != null)
			? event.plugindata.data.videoroom : null;
		if (DEBUG) Log.v(TAG, "handlePluginEvent:" + event);
		if (!TextUtils.isEmpty(eventType)) {
			switch (eventType) {
			case "attached":
				return handlePluginEventAttached(transaction, event);
			case "joined":
				return handlePluginEventJoined(transaction, event);
			case "event":
				return handlePluginEventEvent(transaction, event);
			}
		}
		return false;	// true: handled
	}
	
	/**
	 * eventTypeが"attached"のときの処理
	 * Subscriberがリモート側へjoinした時のレスポンス
	 * @param room
	 * @return
	 */
	protected boolean handlePluginEventAttached(@NonNull final String transaction,
		@NonNull final EventRoom room) {

		if (DEBUG) Log.v(TAG, "handlePluginEventAttached:");
		// FIXME これが来たときはofferが一緒に来ているはずなのでanswerを送り返さないといけない
		if (room.jsep != null) {
			if ("answer".equals(room.jsep.type)) {
				if (DEBUG) Log.v(TAG, "handlePluginEventAttached:answer");
				// Janus-gatewayの相手している時にたぶんこれは来ない
				final SessionDescription answerSdp
					= new SessionDescription(
						SessionDescription.Type.fromCanonicalForm("answer"),
					room.jsep.sdp);
				return onRemoteDescription(answerSdp);
			} else if ("offer".equals(room.jsep.type)) {
				if (DEBUG) Log.v(TAG, "handlePluginEventAttached:offer");
				// Janus-gatewayの相手している時はたぶんいつもこっち
				final SessionDescription sdp
					= new SessionDescription(
						SessionDescription.Type.fromCanonicalForm("offer"),
					room.jsep.sdp);
				return onRemoteDescription(sdp);
			}
		}
		return true;
	}
	
	/**
	 * eventTypeが"joined"のときの処理
	 * @param room
	 * @return
	 */
	protected boolean handlePluginEventJoined(@NonNull final String transaction,
		@NonNull final EventRoom room) {

		if (DEBUG) Log.v(TAG, "handlePluginEventJoined:");
		mRoomState = RoomState.CONNECTED;
		mRoom.publisherId = room.plugindata.data.id;
		onJoin(room);
		return true;	// true: 処理済み
	}
	
	protected void onJoin(@NonNull final EventRoom room) {
		if (DEBUG) Log.v(TAG, "onJoin:");
		mCallback.onJoin(this, room);
	}

	/**
	 * eventTypeが"event"のときの処理
	 * @param room
	 * @return
	 */
	protected boolean handlePluginEventEvent(@NonNull final String transaction,
		@NonNull final EventRoom room) {

		if (DEBUG) Log.v(TAG, "handlePluginEventEvent:");
		if (room.jsep != null) {
			if ("answer".equals(room.jsep.type)) {
				final SessionDescription answerSdp
					= new SessionDescription(
						SessionDescription.Type.fromCanonicalForm("answer"),
					room.jsep.sdp);
				return onRemoteDescription(answerSdp);
			} else if ("offer".equals(room.jsep.type)) {
				final SessionDescription offerSdp
					= new SessionDescription(
						SessionDescription.Type.fromCanonicalForm("offer"),
					room.jsep.sdp);
				return onRemoteDescription(offerSdp);
			}
		}
		return true;	// true: 処理済み
	}
	
	/**
	 * リモート側のSessionDescriptionの準備ができたときの処理
	 * @param sdp
	 * @return
	 */
	protected abstract boolean onRemoteDescription(@NonNull final SessionDescription sdp);

	/**
	 * WebRTC関係のイベント受信時の処理
	 * @param body
	 * @return
	 */
	protected boolean handleWebRTCEvent(@NonNull final String transaction,
		final JSONObject body) {

		if (DEBUG) Log.v(TAG, "handleWebRTCEvent:" + body);
		return false;	// true: handled
	}

//================================================================================
	/**
	 * set call that is currently in progress
	 * @param call
	 */
	protected void addCall(@NonNull final Call<?> call) {
		synchronized (mCurrentCalls) {
			mCurrentCalls.add(call);
		}
	}
	
	protected void removeCall(@NonNull final Call<?> call) {
		synchronized (mCurrentCalls) {
			mCurrentCalls.remove(call);
		}
		if (!call.isCanceled()) {
			try {
				call.cancel();
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
		}
	}
	
	/**
	 * cancel call if call is in progress
	 */
	protected void cancelCall() {
		synchronized (mCurrentCalls) {
			for (final Call<?> call: mCurrentCalls) {
				if ((call != null) && !call.isCanceled()) {
					try {
						call.cancel();
					} catch (final Exception e) {
						Log.w(TAG, e);
					}
				}
			}
			mCurrentCalls.clear();
		}
	}

	protected void reportError(@NonNull final Throwable t) {
		try {
			mCallback.onError(this, t);
		} catch (final Exception e) {
			Log.w(TAG, e);
		}
	}

	private final SdpObserver mSdpObserver = new SdpObserver() {
		@Override
		public void onCreateSuccess(final SessionDescription origSdp) {
			if (DEBUG) Log.v(TAG, "onCreateSuccess:");
			if (mLocalSdp != null) {
				reportError(new RuntimeException("Multiple SDP create."));
				return;
			}
			String sdpDescription = origSdp.description;
			if (preferIsac) {
				sdpDescription = SdpUtils.preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true);
			}
			if (mCallback.isVideoCallEnabled(JanusPlugin.this)) {
				sdpDescription =
					SdpUtils.preferCodec(sdpDescription,
						mCallback.getPeerConnectionParameters(JanusPlugin.this)
							.getSdpVideoCodecName(), false);
			}
			final SessionDescription sdp = new SessionDescription(origSdp.type, sdpDescription);
			mLocalSdp = sdp;
			executor.execute(() -> {
				if (peerConnection != null && !isError) {
					Log.d(TAG, "Set local SDP from " + sdp.type);
					peerConnection.setLocalDescription(mSdpObserver, sdp);
				}
			});
		}
		
		@Override
		public void onSetSuccess() {
			if (DEBUG) Log.v(TAG, "onSetSuccess:");
			executor.execute(() -> {
				if (peerConnection == null || isError) {
					return;
				}
				if (isInitiator) {
					// For offering peer connection we first create offer and set
					// local SDP, then after receiving answer set remote SDP.
					if (peerConnection.getRemoteDescription() == null) {
						// We've just set our local SDP so time to send it.
						if (DEBUG) Log.d(TAG, "Local SDP set successfully");
						mCallback.onLocalDescription(JanusPlugin.this, mLocalSdp);
					} else {
						// We've just set remote description, so drain remote
						// and send local ICE candidates.
						if (DEBUG) Log.d(TAG, "Remote SDP set successfully");
						drainCandidates();
					}
				} else {
					// For answering peer connection we set remote SDP and then
					// create answer and set local SDP.
					if (peerConnection.getLocalDescription() != null) {
						// We've just set our local SDP so time to send it, drain
						// remote and send local ICE candidates.
						if (DEBUG) Log.d(TAG, "Local SDP set successfully");
						mCallback.onLocalDescription(JanusPlugin.this, mLocalSdp);
						drainCandidates();
					} else {
						// We've just set remote SDP - do nothing for now -
						// answer will be created soon.
						if (DEBUG) Log.d(TAG, "Remote SDP set successfully");
					}
				}
			});
		}
		
		@Override
		public void onCreateFailure(final String error) {
			reportError(new RuntimeException("createSDP error: " + error));
		}
		
		@Override
		public void onSetFailure(final String error) {
			reportError(new RuntimeException("createSDP error: " + error));
		}
	};
//================================================================================
	public static class Publisher extends JanusPlugin {

		/**
		 * コンストラクタ
		 * @param session
		 */
		public Publisher(@NonNull VideoRoom videoRoom,
			@NonNull final Session session,
			@NonNull final JanusPluginCallback callback,
			@NonNull final MediaConstraints sdpMediaConstraints,
			@NonNull final PeerConnection peerConnection,
			@Nullable final DataChannel dataChannel,
			@Nullable final RtcEventLog rtcEventLog) {

			super(videoRoom, session, callback,
				sdpMediaConstraints,
				peerConnection, dataChannel, rtcEventLog);
			if (DEBUG) Log.v(TAG, "Publisher:");
		}
		
		@NonNull
		@Override
		protected String getPType() {
			return "publisher";
		}
	
		@Override
		protected boolean handlePluginEventEvent(@NonNull final String transaction,
			@NonNull final EventRoom event) {

			super.handlePluginEventEvent(transaction, event);
			checkPublishers(event);
			return true;
		}
	
		@Override
		protected boolean onRemoteDescription(@NonNull final SessionDescription sdp) {
			if (DEBUG) Log.v(TAG, "onRemoteDescription:");
			mRemoteSdp = sdp;
//			// 通話準備完了
			mCallback.onRemoteDescription(this, sdp);
			return true;
		}
	
		private void checkPublishers(final EventRoom room) {
			if (DEBUG) Log.v(TAG, "checkPublishers:");
			if ((room.plugindata != null)
				&& (room.plugindata.data != null)) {
	
				@NonNull
				final List<PublisherInfo> changed = mRoom.updatePublisher(room.plugindata.data.publishers);
				if (room.plugindata.data.leaving != null) {
					for (final PublisherInfo info: changed) {
						if (room.plugindata.data.leaving.equals(info.id)) {
							// XXX ここで削除できたっけ?
							changed.remove(info);
						}
					}
					// FIXME 存在しなくなったPublisherの処理
				}
				if (!changed.isEmpty()) {
					if (DEBUG) Log.v(TAG, "checkPublishers:number of publishers changed");
					for (final PublisherInfo info: changed) {
						executor.execute(() -> {
							if (DEBUG) Log.v(TAG, "checkPublishers:attach new Subscriber");
							mCallback.createSubscriber(
								Publisher.this, info.id);
						});
					}
				}
			}
		}
	}
	
	public static class Subscriber extends JanusPlugin {
		public final BigInteger feederId;

		/**
		 * コンストラクタ
		 * @param session
		 */
		public Subscriber(@NonNull VideoRoom videoRoom,
			@NonNull final Session session,
			@NonNull final JanusPluginCallback callback,
			@NonNull final BigInteger feederId,
			@NonNull final MediaConstraints sdpMediaConstraints,
			@NonNull final PeerConnection peerConnection,
			@Nullable final DataChannel dataChannel,
			@Nullable final RtcEventLog rtcEventLog) {

			super(videoRoom, session, callback,
				sdpMediaConstraints,
				peerConnection, dataChannel, rtcEventLog);

			if (DEBUG) Log.v(TAG, "Subscriber:");
			this.feederId = feederId;
		}
		
		@NonNull
		@Override
		protected String getPType() {
			return "subscriber";
		}

		protected BigInteger getFeedId() {
			return feederId;
		}

		@Override
		protected boolean onRemoteDescription(@NonNull final SessionDescription sdp) {
			if (DEBUG) Log.v(TAG, "onRemoteDescription:\n" + sdp.description);
			mRemoteSdp = sdp;
//			// 通話準備完了
			mCallback.onRemoteDescription(this, sdp);
			if (sdp.type == SessionDescription.Type.OFFER) {
				createAnswer();
			}
			return true;
		}

	}
}
