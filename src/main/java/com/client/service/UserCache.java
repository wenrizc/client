package com.client.service;

import com.client.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;

/**
 * 用户信息缓存服务
 */
@Service
public class UserCache {
    private static final Logger logger = LoggerFactory.getLogger(UserCache.class);
    private static final String CACHE_FILE = "./user_cache.dat";

    /**
     * 保存用户信息到缓存
     */
    public void saveUserToCache(User user) {
        if (user == null) {
            return;
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(CACHE_FILE))) {
            oos.writeObject(user);
            logger.info("用户信息已缓存: {}", user.getUsername());
        } catch (IOException e) {
            logger.error("保存用户缓存失败", e);
        }
    }

    /**
     * 从缓存加载用户信息
     */
    public User loadUserFromCache() {
        File file = new File(CACHE_FILE);
        if (!file.exists()) {
            return null;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            User user = (User) ois.readObject();
            logger.info("从缓存加载用户信息: {}", user.getUsername());
            return user;
        } catch (IOException | ClassNotFoundException e) {
            logger.error("加载用户缓存失败", e);
            return null;
        }
    }

    /**
     * 清除用户缓存
     */
    public void clearCache() {
        File file = new File(CACHE_FILE);
        if (file.exists() && file.delete()) {
            logger.info("用户缓存已清除");
        }
    }
}