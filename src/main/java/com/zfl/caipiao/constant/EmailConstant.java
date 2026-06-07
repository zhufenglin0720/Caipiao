package com.zfl.caipiao.constant;

public class EmailConstant {

    public static final String EMAIL_TEMPLATE = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>今日彩票预测</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 0; padding: 20px; background-color: #f5f5f5; }
                    .container { max-width: 600px; margin: 0 auto; background-color: white; border-radius: 10px; overflow: hidden; box-shadow: 0 4px 12px rgba(0,0,0,0.1); }
                    .header { background: linear-gradient(135deg, #667eea, #764ba2); color: white; text-align: center; padding: 30px 20px; }
                    .content { padding: 30px; }
                    .prediction-box { border: 2px solid #e0e0e0; border-radius: 8px; padding: 20px; margin: 20px 0; background-color: #fafafa; }
                    .game-title { font-size: 18px; font-weight: bold; color: #333; margin-bottom: 15px; }
                    .numbers { display: flex; justify-content: center; gap: 15px; margin: 15px 0; }
                    .number { width: 50px; height: 50px; border-radius: 50%; background: linear-gradient(135deg, #667eea, #764ba2); color: white; display: flex; align-items: center; justify-content: center; font-size: 20px; font-weight: bold; }
                    .footer { text-align: center; padding: 20px; background-color: #f8f9fa; color: #6c757d; font-size: 14px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>🎯 今日彩票预测</h1>
                    </div>
                    <div class="content">
                        <div class="prediction-box">
                            <div class="game-title">📊 3D</div>
                            <div class="numbers">
                                <div class="number">{{num_1}}</div>
                                <div class="number">{{num_2}}</div>
                                <div class="number">{{num_3}}</div>
                            </div>
                            <div class="numbers">
                                <div class="number">{{num_4}}</div>
                                <div class="number">{{num_5}}</div>
                                <div class="number">{{num_6}}</div>
                            </div>
                            <div class="numbers">
                                <div class="number">{{num_7}}</div>
                                <div class="number">{{num_8}}</div>
                                <div class="number">{{num_9}}</div>
                            </div>
                            <div class="numbers">
                                <div class="number">{{num_10}}</div>
                                <div class="number">{{num_11}}</div>
                                <div class="number">{{num_12}}</div>
                            </div>
                            <div class="numbers">
                                <div class="number">{{num_13}}</div>
                                <div class="number">{{num_14}}</div>
                                <div class="number">{{num_15}}</div>
                            </div>
                        </div>
                        <div class="prediction-box">
                            <div class="game-title">🎰 排列三</div>
                            <div class="numbers">
                                <div class="number">{{num_16}}</div>
                                <div class="number">{{num_17}}</div>
                                <div class="number">{{num_18}}</div>
                            </div>
                            <div class="numbers">
                                <div class="number">{{num_19}}</div>
                                <div class="number">{{num_20}}</div>
                                <div class="number">{{num_21}}</div>
                            </div>
                            <div class="numbers">
                                <div class="number">{{num_22}}</div>
                                <div class="number">{{num_23}}</div>
                                <div class="number">{{num_24}}</div>
                            </div>
                            <div class="numbers">
                                <div class="number">{{num_25}}</div>
                                <div class="number">{{num_26}}</div>
                                <div class="number">{{num_27}}</div>
                            </div>
                            <div class="numbers">
                                <div class="number">{{num_28}}</div>
                                <div class="number">{{num_29}}</div>
                                <div class="number">{{num_30}}</div>
                            </div>
                        </div>
                        <div style="text-align: center; margin-top: 20px;">
                            <p style="color: #ff6b6b; font-weight: bold; font-size: 13px">💡 温馨提示：理性购彩，祝您好运！</p>
                        </div>
                    </div>
                    <div class="footer">
                        <p>发送时间: {{TIMESTAMP}}</p>
                    </div>
                </div>
            </body>
            </html>
            """;

    public final static String NOTICE_MSG = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>彩票开奖通知</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                        background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
                        margin: 0;
                        padding: 20px;
                        min-height: 100vh;
                    }
                    .game-title { font-size: 18px; font-weight: bold; color: #333; margin-bottom: 15px; }
                    .container {
                        max-width: 600px;
                        margin: 0 auto;
                    }
                    .card {
                        background: white;
                        border-radius: 12px;
                        box-shadow: 0 6px 20px rgba(0, 0, 0, 0.1);
                        padding: 30px;
                        margin-bottom: 20px;
                        transition: transform 0.3s ease;
                    }
                    .card:hover {
                        transform: translateY(-5px);
                    }
                    h1 {
                        text-align: center;
                        color: #2c3e50;
                        margin-bottom: 25px;
                        font-size: 24px;
                        border-bottom: 2px solid #3498db;
                        padding-bottom: 15px;
                    }
                    .item {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        padding: 15px 0;
                        border-bottom: 1px dashed #ecf0f1;
                    }
                    .item:last-child {
                        border-bottom: none;
                    }
                    .label {
                        font-weight: 600;
                        color: #34495e;
                        min-width: 140px;
                    }
                    .numbers {
                        background: #f8f9fa;
                        padding: 10px 15px;
                        border-radius: 8px;
                        font-family: 'Courier New', monospace;
                        font-weight: 500;
                        letter-spacing: 1px;
                        color: #2980b9;
                        border: 1px solid #e1e8ed;
                    }
                    .actual {
                        background: #e8f4fd;
                        color: #2980b9;
                        font-weight: bold;
                    }
                    .warning {
                        text-align: center;
                        color: #e74c3c;
                        font-size: 14px;
                        margin-top: 20px;
                        padding: 10px;
                        background: #fdf2f2;
                        border-radius: 6px;
                        border-left: 4px solid #e74c3c;
                    }
                </style>
            </head>
            <body>
            <div class="container">
                <div class="card">
                    <h1>彩票开奖通知：{{result}}</h1>
                    <div class="game-title">🎰 排列三</div>
                    <div class="item">
                        <span class="label">预测号码：</span>
                        <span class="numbers">{{str1}}</span>
                    </div>
                    <div class="item">
                        <span class="label">实际开奖号码：</span>
                        <span class="numbers actual">{{str2}}</span>
                    </div>
                    <div class="game-title" style="margin-top: 20px">📊 3D</div>
                    <div class="item">
                        <span class="label">预测号码：</span>
                        <span class="numbers">{{str3}}</span>
                    </div>
                    <div class="item">
                        <span class="label">实际开奖号码：</span>
                        <span class="numbers actual">{{str4}}</span>
                    </div>
                </div>
            </div>
            </body>
            </html>
            """;


    public static final String DingWeiAskContent =
                            """
                            <!DOCTYPE html>
                            <html lang="zh-CN">
                            <head>
                                <meta charset="UTF-8">
                                <title>数字组合</title>
                            </head>
                            <body style="margin:0; padding:20px; background:#f5f7fa; font-family:Microsoft YaHei;">
                                <div style="max-width:600px; margin:0 auto; background:#fff; border-radius:12px; padding:30px; box-shadow:0 2px 12px rgba(0,0,0,0.08);">
                                    <h2 style="text-align:center; color:#2c3e50; margin-bottom:30px;">{type}定位七码推荐</h2>
                                    <div style="margin-bottom:20px;">
                                        <div style="font-size:16px; color:#34495e; font-weight:bold; margin-bottom:8px;">百位：</div>
                                        <div style="font-size:15px; color:#27ae60; padding:12px; background:#f8fff9; border-radius:8px;">{bw}</div>
                                    </div>
                                    <div style="margin-bottom:20px;">
                                        <div style="font-size:16px; color:#34495e; font-weight:bold; margin-bottom:8px;">十位：</div>
                                        <div style="font-size:15px; color:#2980b9; padding:12px; background:#f7fbff; border-radius:8px;">{sw}</div>
                                    </div>
                                    <div style="margin-bottom:20px;">
                                        <div style="font-size:16px; color:#34495e; font-weight:bold; margin-bottom:8px;">个位：</div>
                                        <div style="font-size:15px; color:#f39c12; padding:12px; background:#fffbf5; border-radius:8px;">{gw}</div>
                                    </div>
                                </div>
                            </body>
                            </html>
                            """;
}
