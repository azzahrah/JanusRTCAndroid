package com.serenegiant.janus.request;

import android.support.annotation.NonNull;

import com.serenegiant.janus.TransactionManager;

public class Creator {
	@NonNull
	public final String janus;
	@NonNull
	public final String transaction;
	
	public Creator() {
		this.janus = "create";
		this.transaction = TransactionManager.get(12, null);
	}
	
	@Override
	public String toString() {
		return "Creator{" +
			"janus='" + janus + '\'' +
			", transaction='" + transaction + '\'' +
			'}';
	}
}
