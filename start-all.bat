@echo off
chcp 65001 >nul
title Ecommerce Platform
cd /d %~dp0

if not exist "service-user\target\service-user-1.0.0.jar" (
    echo [INFO] Building...
    call mvn clean package -DskipTests -q
)

echo [INFO] Starting admin...
start "admin" /min java -jar ecommerce-admin\target\ecommerce-admin-1.0.0.jar
timeout /t 5 /nobreak >nul

echo [INFO] Starting user...
start "user" /min java -jar service-user\target\service-user-1.0.0.jar
timeout /t 8 /nobreak >nul

echo [INFO] Starting product...
start "product" /min java -jar service-product\target\service-product-1.0.0.jar
timeout /t 8 /nobreak >nul

echo [INFO] Starting order...
start "order" /min java -jar service-order\target\service-order-1.0.0.jar
timeout /t 5 /nobreak >nul

echo [INFO] Starting seckill...
start "seckill" /min java -jar service-seckill\target\service-seckill-1.0.0.jar
timeout /t 5 /nobreak >nul

echo [INFO] Starting payment...
start "payment" /min java -jar service-payment\target\service-payment-1.0.0.jar
timeout /t 5 /nobreak >nul

echo [INFO] Starting gateway...
start "gateway" /min java -jar gateway\target\gateway-1.0.0.jar

echo.
echo All started! Press any key to stop...
pause >nul

echo Stopping...
taskkill /f /fi "windowtitle eq admin" >nul 2>&1
taskkill /f /fi "windowtitle eq user" >nul 2>&1
taskkill /f /fi "windowtitle eq product" >nul 2>&1
taskkill /f /fi "windowtitle eq order" >nul 2>&1
taskkill /f /fi "windowtitle eq seckill" >nul 2>&1
taskkill /f /fi "windowtitle eq payment" >nul 2>&1
taskkill /f /fi "windowtitle eq gateway" >nul 2>&1
echo Done.
timeout /t 2 /nobreak >nul
