package com.zfl.caipiao.controller;

import com.zfl.caipiao.job.GlobalJob;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class TestController {

    @Resource
    private GlobalJob globalJob;

    @GetMapping("/applyTask")
    public void applyTask() throws Exception {
        globalJob.applyTask();
    }

    @GetMapping("/applyDingWeiTask")
    public void applyDingWeiTask() throws Exception {
        globalJob.applyDingWeiTask();
    }

    @GetMapping("/checkApply")
    public void setDataTask() throws Exception {
        globalJob.setDataTask();
    }
}
