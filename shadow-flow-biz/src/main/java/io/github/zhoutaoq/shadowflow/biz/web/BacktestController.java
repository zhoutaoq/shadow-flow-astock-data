package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.auth.AuthContext;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.Backtest;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestProgress;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestResult;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestRunRequest;
import io.github.zhoutaoq.shadowflow.biz.service.BacktestService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 回测接口（数据源固定为行情数据的日K）。
 *
 * <p>v1.6.8 起<b>所有接口都按登录用户隔离</b>（需求：回测记录存在用户维度上，不要跨用户展示）：
 * 列表只列自己的、回看 / 删除别人的记录一律当成「不存在」。
 * 接口本身已在 {@code AuthWebConfig.PROTECTED} 里，进不来未登录请求。
 */
@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private final BacktestService service;

    public BacktestController(BacktestService service) {
        this.service = service;
    }

    /** 执行一次回测并落库 */
    @PostMapping("/run")
    public ApiResponse<BacktestResult> run(@RequestBody BacktestRunRequest req) {
        if (req == null) {
            return ApiResponse.fail("回测参数不能为空");
        }
        try {
            return ApiResponse.ok(service.run(req, requireUserId()), "回测完成");
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 参数问题与数据不全，提示语直接透出给用户
            service.markError(req.getTaskId(), e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            String msg = "回测失败: " + rootMsg(e);
            service.markError(req.getTaskId(), msg);
            return ApiResponse.fail(msg);
        }
    }

    /** 轮询执行进度：回测是同步跑完才返回的，前端在等待期间另开请求查这里 */
    @GetMapping("/progress/{taskId}")
    public ApiResponse<BacktestProgress> progress(@PathVariable String taskId) {
        BacktestProgress p = service.progress(taskId);
        if (p == null) {
            return ApiResponse.fail("进度不存在或已结束, taskId=" + taskId);
        }
        return ApiResponse.ok(p);
    }

    /** 历史回测列表：只列当前用户自己跑的 */
    @GetMapping
    public ApiResponse<List<Backtest>> list(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(service.list(requireUserId(), limit));
    }

    /** 回看某次回测；不属于当前用户的记录按「不存在」处理 */
    @GetMapping("/{id}")
    public ApiResponse<BacktestResult> detail(@PathVariable long id) {
        BacktestResult r = service.detail(requireUserId(), id);
        if (r == null) {
            return ApiResponse.fail("回测记录不存在, id=" + id);
        }
        return ApiResponse.ok(r);
    }

    /** 删除某次回测（连带每日快照与交易明细） */
    @DeleteMapping("/{id}")
    public ApiResponse<Integer> delete(@PathVariable long id) {
        try {
            long userId = requireUserId();
            // 先确认这条归自己：否则「删掉了 0 行」也会回成功，看起来像删了别人的记录
            if (service.detail(userId, id) == null) {
                return ApiResponse.fail("回测记录不存在, id=" + id);
            }
            service.delete(userId, id);
            return ApiResponse.ok(1, "删除成功");
        } catch (Exception e) {
            return ApiResponse.fail("删除失败: " + rootMsg(e));
        }
    }

    /**
     * 当前登录用户：拦截器已保证登录，这里再判一次 ——
     * 万一哪天这个路径被摘出 PROTECTED，也不会把所有用户的数据混在一起返回（0 号用户）。
     */
    private static long requireUserId() {
        long userId = AuthContext.currentUserId();
        if (userId <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
    }

    /** 取最内层异常的原因，避免只看到 JdbcTemplate 的包装异常 */
    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String msg = c.getMessage();
        return msg == null ? c.getClass().getSimpleName() : msg;
    }
}
