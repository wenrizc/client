package com.client.util;

import com.client.config.AppProperties;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

@Component
public class ResourceUtil {

    private static final Logger logger = LoggerFactory.getLogger(ResourceUtil.class);

    private final AppProperties appProperties;

    @Autowired
    public ResourceUtil(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public Image getImage(String imageName) {
        String imagePath = appProperties.getImagePath() + imageName;
        try {
            InputStream is = new ClassPathResource(imagePath).getInputStream();
            return new Image(is);
        } catch (IOException e) {
            logger.error("加载图片失败: " + imagePath, e);
            return null;
        }
    }

    public URL getResourceUrl(String resourcePath) {
        try {
            return new ClassPathResource(resourcePath).getURL();
        } catch (IOException e) {
            logger.error("获取资源URL失败: " + resourcePath, e);
            return null;
        }
    }

    public String getCssUrl(String cssFileName) {
        String cssPath = appProperties.getStylePath() + cssFileName;
        logger.debug("尝试加载CSS文件路径: {}", cssPath);
        try {
            // 使用ClassPathResource来加载类路径资源
            ClassPathResource resource = new ClassPathResource(cssPath);
            if (resource.exists()) {
                logger.debug("通过类路径成功加载: {}", cssPath);
                return resource.getURL().toExternalForm();
            }

            logger.debug("类路径中未找到CSS文件: {}", cssPath);
            return null;
        } catch (IOException e) {
            logger.error("CSS资源加载失败: {}", e.getMessage());
            return null;
        }
    }

    public InputStream getResourceStream(String resourcePath) {
        try {
            return new ClassPathResource(resourcePath).getInputStream();
        } catch (IOException e) {
            logger.error("获取资源流失败: " + resourcePath, e);
            return null;
        }
    }
}