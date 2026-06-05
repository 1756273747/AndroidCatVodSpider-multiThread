package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.quark.Item;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * 夸克个人网盘 Spider
 * 功能：用户扫码登录自己的夸克网盘，浏览指定目录下的视频文件夹
 */
public class QuarkPersonal extends Spider {

    // 配置字段
    private String rootPath = "视频";
    private List<String> categories = Arrays.asList("电影", "电视剧", "综艺", "动漫", "其它");
    private String defaultPic = "";
    private List<String> picExts = Arrays.asList("jpg", "jpeg", "png", "webp");
    private String infoFile = "info.txt";

    // 路径缓存
    private Map<String, String> pathCache = new HashMap<>();

    @Override
    public void init(Context context, String extend) throws Exception {
        parseConfig(extend);
        // 检查登录状态，未登录会触发二维码流程
        if (!QuarkApi.get().isLoggedIn()) {
            QuarkApi.get().initUserInfo();
        }
    }

    /**
     * 解析 ext 配置
     */
    private void parseConfig(String extend) {
        if (extend == null || extend.isEmpty()) return;
        try {
            JsonObject ext = Json.safeObject(extend);
            if (ext.has("rootPath")) rootPath = ext.get("rootPath").getAsString();
            if (ext.has("defaultPic")) defaultPic = ext.get("defaultPic").getAsString();
            if (ext.has("infoFile")) infoFile = ext.get("infoFile").getAsString();
            if (ext.has("categories")) {
                categories = new ArrayList<>();
                JsonArray arr = ext.getAsJsonArray("categories");
                for (JsonElement e : arr) categories.add(e.getAsString());
            }
            if (ext.has("picExts")) {
                picExts = new ArrayList<>();
                JsonArray arr = ext.getAsJsonArray("picExts");
                for (JsonElement e : arr) picExts.add(e.getAsString());
            }
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonal parseConfig error: " + e.getMessage());
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (String cat : categories) {
            classes.add(new Class(cat, cat));
        }
        return Result.string(classes, new LinkedHashMap<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        // tid = "电影" / "电视剧" 等分类名
        String fullPath = rootPath + "/" + tid;

        // 获取该目录下的子文件夹（视频条目）
        List<Item> folders = QuarkApi.get().listPersonalFolders(fullPath);

        List<Vod> list = new ArrayList<>();
        for (Item folder : folders) {
            Vod vod = new Vod();
            String folderPath = fullPath + "/" + folder.getName();
            vod.setVodId(folderPath);
            vod.setVodName(folder.getName());

            // 查找海报
            String poster = findPoster(folderPath);
            vod.setVodPic(poster != null ? poster : defaultPic);

            list.add(vod);
        }

        // 添加刷新条目
        list.add(createRefreshItem(tid));

        return Result.get().vod(list).page().string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String path = ids.get(0);

        // 处理刷新操作
        if (path.startsWith("refresh:")) {
            String category = path.substring("refresh:".length());
            QuarkApi.get().clearPathCache();
            // 重新加载该分类
            return categoryContent(category, "1", false, new HashMap<>());
        }

        // 读取简介文件
        String content = readInfoFile(path);

        // 查找海报
        String poster = findPoster(path);

        // 列出该文件夹内的所有视频文件
        List<Item> videos = QuarkApi.get().listPersonalFiles(path);

        // 构建播放列表
        Vod vod = new Vod();
        vod.setVodId(path);
        vod.setVodName(getFolderName(path));
        vod.setVodPic(poster != null ? poster : defaultPic);
        vod.setVodContent(content);
        vod.setVodPlayFrom("quark原画$$$quark4K");
        vod.setVodPlayUrl(buildPlayUrl(videos));

        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // id 格式: fileId++fileToken++shareId++stoken (复用现有格式)
        // 对于个人网盘，shareId 和 stoken 为空，fileToken 也为空
        // 实际播放需要调用 QuarkApi 的个人网盘播放方法
        String[] split = id.split("\\+\\+");
        return QuarkApi.get().playerContentPersonal(split, flag);
    }

    // ========== 私有方法 ==========

    /**
     * 在指定文件夹中查找海报图片
     */
    private String findPoster(String folderPath) {
        try {
            List<Item> files = QuarkApi.get().listPersonalFiles(folderPath);
            for (Item file : files) {
                String ext = Util.getExt(file.getName()).toLowerCase();
                if (picExts.contains(ext)) {
                    // 返回图片的下载 URL
                    return QuarkApi.get().getPersonalFileUrl(file.getFid());
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("findPoster error: " + e.getMessage());
        }
        return null;
    }

    /**
     * 读取简介文件
     */
    private String readInfoFile(String folderPath) {
        try {
            List<Item> files = QuarkApi.get().listPersonalFiles(folderPath);
            for (Item file : files) {
                if (file.getName().equalsIgnoreCase(infoFile)) {
                    String url = QuarkApi.get().getPersonalFileUrl(file.getFid());
                    // 下载文件内容
                    return OkHttp.string(url, new HashMap<>(), new HashMap<>());
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("readInfoFile error: " + e.getMessage());
        }
        return "";
    }

    /**
     * 构建播放 URL 列表
     */
    private String buildPlayUrl(List<Item> videos) {
        List<String> urls = new ArrayList<>();
        for (Item video : videos) {
            if (Util.isMedia(video.getName())) {
                // 格式: 文件名$fileId++++++
                // 个人网盘不需要 shareId 和 stoken
                urls.add(video.getName() + "$" + video.getFid() + "+++++");
            }
        }
        return String.join("#", urls);
    }

    /**
     * 从路径中提取文件夹名
     */
    private String getFolderName(String path) {
        int lastSlash = path.lastIndexOf("/");
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * 创建刷新条目
     */
    private Vod createRefreshItem(String category) {
        Vod vod = new Vod();
        vod.setVodId("refresh:" + category);
        vod.setVodName("🔄 刷新列表");
        vod.setVodPic("");
        return vod;
    }
}
