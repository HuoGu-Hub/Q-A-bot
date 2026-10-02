import com.example.qqbot.config.KbProperties;
import com.example.qqbot.kb.build.WikiTextCleaner;
import java.nio.file.Files;
import java.nio.file.Path;

/** 清洗规则调试器：把某个 raw/*.wiki 过一遍清洗，直接看输出。
 *  用法：java -cp <CP> tests/CleanDebug.java <raw文件> [标题] */
public class CleanDebug {
    public static void main(String[] args) throws Exception {
        KbProperties props = new KbProperties();
        WikiTextCleaner cleaner = new WikiTextCleaner(props.getBuild());
        String raw = Files.readString(Path.of(args[0]));
        String title = args.length > 1 ? args[1] : "Page";
        System.out.println(cleaner.clean(title, raw).text());
    }
}