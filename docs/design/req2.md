用以下信息测试一次日k数据落库

code:600584
日期，2026-01-01-2026-09-04

数据库连接信息
docker run -d --name mysql-server --restart always -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root123456 -e MYSQL_DATABASE=mydb -e MYSQL_USER=myuser -e MYSQL_PASSWORD=mypassword -e TZ=Asia/Shanghai -v C:/workspace/docker/mysql/data:/var/lib/mysql mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci

