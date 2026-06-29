@echo off
chcp 65001 >nul
title Quado - Code Graph Indexer

:: ============================================================
:: QUADO LAUNCHER - Cau hinh theo project
:: ============================================================
:: De dung cho nhieu project, copy file nay va doi DB_PATH.
:: Vi du:
::   launch-project-A.bat  ->  DB_PATH=D:\data\quado-db\project-a
::   launch-project-B.bat  ->  DB_PATH=D:\data\quado-db\project-b
:: ============================================================

:: Duong dan toi file jar (relative hoac absolute)
set JAR_PATH=%~dp0target\quado-1.0.0-SNAPSHOT.jar

:: Duong dan thu muc DB cho project nay
:: THAY DOI GIA TRI NAY cho moi project!
set DB_PATH=%~dp0arcadedb_graph

:: Kiem tra file jar ton tai
if not exist "%JAR_PATH%" (
    echo [ERROR] Khong tim thay file JAR: %JAR_PATH%
    echo Vui long chay: mvn clean package -DskipTests truoc
    pause
    exit /b 1
)

echo ============================================================
echo  QUADO - Code Graph Indexer
echo  DB Path : %DB_PATH%
echo  JAR     : %JAR_PATH%
echo ============================================================
echo.

java -jar "%JAR_PATH%" --arcadedb.path="%DB_PATH%"

if errorlevel 1 (
    echo.
    echo [ERROR] Quado da thoat voi loi. Xem log trong thu muc app_logs\
    pause
)