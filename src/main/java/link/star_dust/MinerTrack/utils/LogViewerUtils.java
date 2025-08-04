/**
 * DON'T REMOVE THIS
 * 
 * /MinerTrack/src/main/java/link/star_dust/MinerTrack/utils/LogViewerUtils.java
 * 
 * MinerTrack Source Code - Public under GPLv3 license
 * Original Author: Author87668
 * Contributors: Author87668
 * 
 * DON'T REMOVE THIS
**/
package link.star_dust.MinerTrack.utils;

import org.bukkit.command.CommandSender;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LogViewerUtils {
    public static class LogCache {
        public String logName;
        public List<String> lines;
        public int totalPages;
        public int currentPage;
        public LogCache(String logName, List<String> lines, int totalPages, int currentPage) {
            this.logName = logName;
            this.lines = lines;
            this.totalPages = totalPages;
            this.currentPage = currentPage;
        }
    }

    private static final Map<CommandSender, LogCache> logCacheMap = new ConcurrentHashMap<>();

    public static LogCache getCache(CommandSender sender) {
        return logCacheMap.get(sender);
    }
    public static void putCache(CommandSender sender, LogCache cache) {
        logCacheMap.put(sender, cache);
    }
    public static void clearCache(CommandSender sender) {
        logCacheMap.remove(sender);
    }

    public static List<String> readLogFile(File logFile) throws IOException {
        return Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
    }

    public static int getTotalPages(int totalLines, int perPage) {
        return (int) Math.ceil((double) totalLines / perPage);
    }

    public static int[] getPageRange(int totalLines, int page, int perPage) {
        int start = totalLines - (page * perPage);
        int end = totalLines - ((page - 1) * perPage);
        start = Math.max(0, start);
        end = Math.min(totalLines, end);
        return new int[]{start, end};
    }
}
