/**
 * Copyright (c) 2013, Redsolution LTD. All rights reserved.
 *
 * This file is part of Xabber project; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License, Version 3.
 *
 * Xabber is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.xabber.android.data.connection;

import android.support.annotation.NonNull;
import android.util.Log;

import com.xabber.android.data.SettingsManager;
import com.xabber.android.data.account.AccountErrorEvent;
import com.xabber.android.data.account.AccountItem;
import com.xabber.android.data.log.AndroidLoggingHandler;
import com.xabber.android.data.log.LogManager;

import org.greenrobot.eventbus.EventBus;
import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.sasl.SASLErrorException;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.util.DNSUtil;
import org.jivesoftware.smack.util.dns.dnsjava.DNSJavaResolver;
import org.jivesoftware.smack.util.dns.minidns.MiniDnsResolver;

import java.io.IOException;
import java.util.logging.Level;

import de.measite.minidns.AbstractDNSClient;

class ConnectionThread {

    @NonNull
    private final XMPPTCPConnection connection;
    @SuppressWarnings("WeakerAccess")
    @NonNull
    final ConnectionItem connectionItem;
    private Thread thread;

    ConnectionThread(@NonNull XMPPTCPConnection connection, @NonNull ConnectionItem connectionItem) {
        this.connection = connection;
        this.connectionItem = connectionItem;
        createNewThread();
    }

    private void createNewThread() {
        LogManager.i(this, "Creating new connection thread");
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (NetworkManager.isNetworkAvailable()) {
                    connectAndLogin();
                } else {
                    connectionItem.updateState(ConnectionState.waiting);
                    LogManager.i(this, "No network connection");
                }
            }
        });
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setDaemon(true);
    }

    /**
     *
     * @return true if connection thread started, false if already running - nothing changed
     */
    boolean start() {
        if (thread.getState() == Thread.State.TERMINATED) {
            LogManager.i(this, "Connection thread is finished, creating new one...");
            createNewThread();
        }

        if (thread.getState() == Thread.State.NEW) {
            LogManager.i(this, "Connection thread is new, starting...");
            thread.start();
            return true;
        } else {
            LogManager.i(this, "Connection thread is running already");
            return false;
        }
    }

    @SuppressWarnings("WeakerAccess")
    void connectAndLogin() {
        AndroidLoggingHandler.reset(new AndroidLoggingHandler());
        java.util.logging.Logger.getLogger(XMPPTCPConnection.class.getName()).setLevel(Level.FINEST);
        java.util.logging.Logger.getLogger(AbstractDNSClient.class.getName()).setLevel(Level.FINEST);
        java.util.logging.Logger.getLogger(AbstractXMPPConnection.class.getName()).setLevel(Level.FINEST);
        java.util.logging.Logger.getLogger(DNSUtil.class.getName()).setLevel(Level.FINEST);

        switch (SettingsManager.connectionDnsResolver()) {
            case dnsJavaResolver:
                LogManager.i(this, "Use DNS Java resolver");
                DNSJavaResolver.setup();
                break;
            case miniDnsResolver:
                LogManager.i(this, "Use Mini DNS resolver");
                MiniDnsResolver.setup();
                break;
        }

        try {
            LogManager.i(this, "Trying to connect and login...");
            if (!connection.isConnected()) {
                connectionItem.updateState(ConnectionState.connecting);
                connection.connect();
            } else {
                LogManager.i(this, "Already connected");
            }

            if (!connection.isAuthenticated()) {
                connection.login();
            } else {
                LogManager.i(this, "Already authenticated");
            }
        } catch (SASLErrorException e)  {
            LogManager.exception(this, e);

            AccountErrorEvent accountErrorEvent = new AccountErrorEvent(connectionItem.getAccount(),
                    AccountErrorEvent.Type.AUTHORIZATION, e.getMessage());

            com.xabber.android.data.account.AccountManager.getInstance().addAccountError(accountErrorEvent);
            com.xabber.android.data.account.AccountManager.getInstance().setEnabled(connectionItem.getAccount(), false);
            EventBus.getDefault().postSticky(accountErrorEvent);

            // catching RuntimeExceptions seems to be strange, but we got a lot of error coming from
            // Smack or mini DSN client inside of Smack.
        } catch (XMPPException | SmackException | IOException | RuntimeException e) {
            LogManager.exception(this, e);

            if (!((AccountItem)connectionItem).isSuccessfulConnectionHappened()) {
                LogManager.i(this, "There was no successful connection, disabling account");

                AccountErrorEvent accountErrorEvent = new AccountErrorEvent(connectionItem.getAccount(),
                        AccountErrorEvent.Type.CONNECTION, Log.getStackTraceString(e));

                com.xabber.android.data.account.AccountManager.getInstance().addAccountError(accountErrorEvent);
                com.xabber.android.data.account.AccountManager.getInstance().setEnabled(connectionItem.getAccount(), false);
                EventBus.getDefault().postSticky(accountErrorEvent);
            }
        } catch (InterruptedException e) {
            LogManager.exception(this, e);
        }

        LogManager.i(this, "Connection thread finished");
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + connectionItem.getAccount();
    }
}
