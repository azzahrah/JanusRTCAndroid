package com.serenegiant.janus;

import com.serenegiant.janus.request.Attach;
import com.serenegiant.janus.request.Creator;
import com.serenegiant.janus.request.Destroy;
import com.serenegiant.janus.request.Detach;
import com.serenegiant.janus.request.Hangup;
import com.serenegiant.janus.request.Message;
import com.serenegiant.janus.request.Trickle;
import com.serenegiant.janus.request.TrickleCompleted;
import com.serenegiant.janus.response.EventRoom;
import com.serenegiant.janus.response.Plugin;
import com.serenegiant.janus.response.ServerInfo;
import com.serenegiant.janus.response.Session;

import java.math.BigInteger;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface VideoRoom {
	@POST("/janus")
	public Call<Session> create(@Body final Creator create);

	@GET("/janus/info")
	public Call<ServerInfo> getInfo();

	@POST("/janus/{session_id}")
	public Call<Plugin> attach(
		@Path("session_id") final BigInteger sessionId,
		@Body final Attach attach);
	
	@POST("/janus/{session_id}/{plugin_id}")
	public Call<EventRoom> join(
		@Path("session_id") final BigInteger sessionId,
		@Path("plugin_id") final BigInteger pluginId,
		@Body final Message message);

	@POST("/janus/{session_id}/{plugin_id}")
	public Call<EventRoom> offer(
		@Path("session_id") final BigInteger sessionId,
		@Path("plugin_id") final BigInteger pluginId,
		@Body final Message message);

	@POST("/janus/{session_id}/{plugin_id}")
	public Call<EventRoom> trickle(
		@Path("session_id") final BigInteger sessionId,
		@Path("plugin_id") final BigInteger pluginId,
		@Body final Trickle trickle);
	
	@POST("/janus/{session_id}/{plugin_id}")
	public Call<EventRoom> trickleCompleted(
		@Path("session_id") final BigInteger sessionId,
		@Path("plugin_id") final BigInteger pluginId,
		@Body final TrickleCompleted trickle);

	@POST("/janus/{session_id}/{plugin_id}")
	public Call<ResponseBody> send(
		@Path("session_id") final BigInteger sessionId,
		@Path("plugin_id") final BigInteger pluginId,
		@Body final Message message);
	
	@POST("/janus/{session_id}/{plugin_id}")
	public Call<Void> detach(
		@Path("session_id") final BigInteger sessionId,
		@Path("plugin_id") final BigInteger pluginId,
		@Body final Detach detach);
	
	@POST("/janus/{session_id}/{plugin_id}")
	public Call<Void> hangup(
		@Path("session_id") final BigInteger sessionId,
		@Path("plugin_id") final BigInteger pluginId,
		@Body final Hangup hangup);
	
	@POST("/janus/{session_id}")
	public Call<Void> destroy(
		@Path("session_id") final BigInteger sessionId,
		@Body final Destroy destroy);
}
