package com.example.qqbot.media;

/**
 * 媒体文件的目录与清理策略 —— **业务侧自己声明的只读视图**，由 {@code config.MediaProperties} 实现。
 *
 * <h2>为什么业务包不直接注入 MediaProperties</h2>
 * 那个类在 {@code config} 包里。收成「业务侧声明接口、config 侧实现它」之后，
 * {@code media} 包对 config 的 import 就没有了，依赖方向也变成 {@code config → media}。
 *
 * <p>⚠️ 接口而不是 record：配置是**热生效**的（{@code SettingsService} 就地改字段、
 * 各 Bean 长期持引用实时读），写成快照就再也看不到后台的修改，而且不报错。
 *
 * <p><b>方法名保留 {@code getXxx}</b>：这样 {@code MediaProperties} 一行都不用改就能实现它，
 * 也就不存在"适配器写漏一项"的可能。想反向转回可变对象必须 import {@code config} ——
 * 那会被 ArchUnit 的「业务包不得直接依赖 config」当场抓住。
 *
 * <p>两个分组（{@link TempImages} / {@link KbImages}）本身也**搬到了本包** ——
 * 它们是媒体层的领域词汇，只是碰巧从 yml 绑定。
 *
 * <h2>⚠️ 临时图片与知识库图片必须物理隔离</h2>
 * 两者生命周期正好相反：临时图片随时可删、由定时任务清理；知识库图片是长期资产，
 * <b>任何清理逻辑都绝不能碰它</b>。隔离不能只靠"约定" ——
 * {@code MediaStorageGuard} 会在启动时校验两者不是同一个目录，配错就直接拒绝启动。
 *
 * <p>完整理由与粒度说明见 {@code command.CommandPolicy}（债务⑥ 的模板样例）。
 */
public interface MediaPolicy {

    /** 单条消息最多处理几张图。0 = 不限制（不建议） */
    int getMaxImagesPerMessage();

    /** 临时图片（{@code tmp-images}）：用户发来的图、中间产物，随时可删 */
    TempImages getTempImages();

    /** 知识库图片（{@code kb-images}）：长期资产，永久保留 */
    KbImages getKbImages();
}
