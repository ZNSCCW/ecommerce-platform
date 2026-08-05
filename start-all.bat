@echo off
chcp 65001 >nul
title 电商平台 - 一键启动
cd /d %~dp0

echo ========================================
echo  微服务架构电商平台 - 一键启动
echo ========================================
echo.
echo 正在检查中间件状态...
echo.

:: 检查 Nacos 是否在运行
curl -s http://localhost:8848/nacos >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARN] Nacos 未运行！请先执行: docker compose up -d nacos
    echo.
)

:: 检查 MySQL
curl -s http://localhost:8088 >nul 2>&1
echo [INFO] 确保中间件已启动：MySQL/Redis/ES/RocketMQ
echo.

:: 编译（如果 jar 不存在）
if not exist "service-user\target\service-user-1.0.0.jar" (
    echo [INFO] 编译项目中...
    call mvn clean package -DskipTests -q
)

echo.
echo ========================================
echo  启动所有服务...
echo ========================================
echo.

:: 按依赖顺序启动（每个服务独立窗口）
start "ecommerce-admin"   /min java -jar ecommerce-admin\target\ecommerce-admin-1.0.0.jar
timeout /t 3 /nobreak >nul

start "service-user"      /min java -jar service-user\target\service-user-1.0.0.jar
timeout /t 5 /nobreak >nul

start "service-product"   /min java -jar service-product\target\service-product-1.0.0.jar
timeout /t 5 /nobreak >nul

start "service-order"     /min java -jar service-order\target\service-order-1.0.0.jar
timeout /t 3 /nobreak >nul

start "service-seckill"   /min java -jar service-seckill\target\service-seckill-1.0.0.jar
timeout /t 3 /nobreak >nul

start "service-payment"   /min java -jar service-payment\target\service-payment-1.0.0.jar
timeout /t 3 /nobreak >nul

start "gateway"           /min java -jar gateway\target\gateway-1.0.0.jar

echo.
echo ========================================
echo  启动完成！各服务窗口已最小化到任务栏
echo ========================================
echo.
echo  网关:      http://localhost:8080
echo  Admin监控: http://localhost:8090  (admin/admin123)
echo  Nacos:     http://localhost:8848/nacos
echo.
echo  注册:  curl -X POST http://localhost:8080/api/user/register ...
echo  登录:  curl -X POST http://localhost:8080/api/user/login ...
echo.
echo  按任意键关闭所有服务...
pause >nul

echo.
echo 关闭所有服务...
taskkill /f /fi "windowtitle eq ecommerce-admin" >nul 2>&1
taskkill /f /fi "windowtitle eq service-user" >nul 2>&1
taskkill /f /fi "windowtitle eq service-product" >nul 2>&1
taskkill /f /fi "windowtitle eq service-order" >nul 2>&1
taskkill /f /fi "windowtitle eq service-seckill" >nul 2>&1
taskkill /f /fi "windowtitle eq service-payment" >nul 2>&1
taskkill /f /fi "windowtitle eq gateway" >nul 2>&1

echo 所有服务已关闭。
timeout /t 2 /nobreak >nul
