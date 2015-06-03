package org.visallo.core.model.user;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import org.apache.commons.lang.SerializationException;
import org.visallo.core.config.Configuration;
import org.visallo.core.exception.VisalloException;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.apache.commons.lang.SerializationUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;

public class UserSessionCounterRepository {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(UserSessionCounterRepository.class);
    private static final String DEFAULT_BASE_PATH = "/visallo/userSessions";
    @VisibleForTesting
    static final int SESSION_UPDATE_DURATION = 15000;
    @VisibleForTesting
    static final int UNSEEN_SESSION_DURATION = 60000;

    private final CuratorFramework curator;
    private final String basePath;

    @VisibleForTesting
    static class SessionData implements Serializable {
        final boolean autoDelete;

        SessionData(boolean autoDelete) {
            this.autoDelete = autoDelete;
        }
    }

    @Inject
    public UserSessionCounterRepository(final CuratorFramework curatorFramework, final Configuration configuration) {
        this(curatorFramework, configuration, true);
    }

    @VisibleForTesting
    UserSessionCounterRepository(
            final CuratorFramework curatorFramework,
            final Configuration configuration,
            final boolean scheduleOldSessionCleanup
    ) {
        this.curator = curatorFramework;
        String confBasePath = configuration.get(Configuration.USER_SESSION_COUNTER_PATH_PREFIX, DEFAULT_BASE_PATH);
        this.basePath = confBasePath.endsWith("/") ? confBasePath.substring(0, confBasePath.length() - 1) : confBasePath;
        try {
            tryCreate(basePath);
        } catch (Exception e) {
            throw new VisalloException("unable to create base path " + basePath, e);
        }
        if (scheduleOldSessionCleanup) {
            ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true).build();
            Executors.newSingleThreadScheduledExecutor(threadFactory).scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        deleteOldSessions();
                    } catch (Exception e) {
                        LOGGER.error("failed to delete old sessions", e);
                    }
                }
            }, 0, UNSEEN_SESSION_DURATION, TimeUnit.MILLISECONDS);
        }
    }

    public int updateSession(String userId, String sessionId, boolean autoDelete) {
        String sessionPath = sessionPath(userId, sessionId);
        LOGGER.debug("updating user session %s", sessionPath);
        try {
            Stat sessionStat = curator.checkExists().forPath(sessionPath);
            if (sessionStat != null) {
                long now = System.currentTimeMillis();
                long lastUpdated = sessionStat.getMtime();
                if (now - lastUpdated > SESSION_UPDATE_DURATION) {
                    setSessionData(sessionPath, autoDelete);
                }
            } else {
                tryCreate(userPath(userId));
                curator.create().forPath(sessionPath);
                setSessionData(sessionPath, autoDelete);
            }

            int count = countUserSessions(userId);
            LOGGER.debug("user session count for %s is %d", userId, count);
            return count;
        } catch (Exception e) {
            throw new VisalloException("failed to update user session" + sessionPath, e);
        }
    }

    public void deleteSessions(String userId) {
        checkNotNull(userId, "userId cannot be null");
        try {
            List<String> sessionIds = curator.getChildren().forPath(userPath(userId));
            for (String sessionId : sessionIds) {
                deleteSession(userId, sessionId);
            }
        } catch (Exception e) {
            throw new VisalloException("failed to delete user sessions " + userId, e);
        }
    }

    public int deleteSession(String userId, String sessionId) {
        checkNotNull(userId, "userId cannot be null");
        checkNotNull(sessionId, "sessionId cannot be null");
        String sessionPath = sessionPath(userId, sessionId);
        LOGGER.debug("deleting user session %s", sessionPath);
        try {
            Stat sessionStat = curator.checkExists().forPath(sessionPath);
            if (sessionStat != null) {
                curator.delete().forPath(sessionPath);
            }
            int count = countUserSessions(userId);
            if (count < 1) {
                deleteInBackground(userPath(userId));
            }
            LOGGER.debug("user session count for %s is %d", userId, count);
            return count;
        } catch (Exception e) {
            throw new VisalloException("failed to delete user session " + userId, e);
        }
    }

    @VisibleForTesting
    void deleteOldSessions() throws Exception {
        LOGGER.debug("deleting old user sessions");
        List<String> userIds = curator.getChildren().forPath(basePath);
        for (String userId : userIds) {
            deleteOldSessionsForUserId(userId);
        }
    }

    private void deleteOldSessionsForUserId(String userId) throws Exception {
        List<String> sessionIds = curator.getChildren().forPath(userPath(userId));
        for (String sessionId : sessionIds) {
            deleteSessionIfOld(userId, sessionId);
        }
    }

    private void deleteSessionIfOld(String userId, String sessionId) throws Exception {
        String sessionPath = sessionPath(userId, sessionId);
        Stat sessionStat = curator.checkExists().forPath(sessionPath);
        if (sessionStat != null) {
            long now = System.currentTimeMillis();
            long lastUpdated = sessionStat.getMtime();
            if (now - lastUpdated > UNSEEN_SESSION_DURATION) {
                byte[] data = curator.getData().forPath(sessionPath);
                boolean autoDelete = true;
                try {
                    SessionData sessionData = (SessionData) SerializationUtils.deserialize(data);
                    autoDelete = sessionData.autoDelete;
                } catch (SerializationException e) {
                    // data is somehow corrupt
                    LOGGER.debug("unable to deserialize SessionData for session %s", sessionPath);
                    LOGGER.debug("exception is:", e);
                }
                if (autoDelete) {
                    LOGGER.debug("deleting old user session %s", sessionPath);
                    curator.delete().inBackground().forPath(sessionPath);
                }
            }
        }
    }

    private void setSessionData(String sessionPath, boolean autoDelete) throws Exception {
        SessionData sessionData = new SessionData(autoDelete);
        byte[] data = SerializationUtils.serialize(sessionData);
        curator.setData().forPath(sessionPath, data); // Stat last modification time (mtime) will change
    }

    private int countUserSessions(String userId) throws Exception {
        Stat userStat = curator.checkExists().forPath(userPath(userId));
        return userStat != null ? userStat.getNumChildren() : 0;
    }

    private void tryCreate(String path) throws Exception {
        LOGGER.debug("creating path %s", path);
        try {
            curator.create().creatingParentsIfNeeded().forPath(path);
        } catch (KeeperException.NodeExistsException e) {
            LOGGER.debug("path %s is already created", path);
        }
    }

    private void deleteInBackground(String path) throws Exception {
        LOGGER.debug("deleting path %s", path);
        curator.delete().inBackground().forPath(path);
    }

    private String userPath(String userId) {
        return basePath + "/" + userId;
    }

    private String sessionPath(String userId, String sessionId) {
        return userPath(userId) + "/" + sessionId;
    }
}
