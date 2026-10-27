# Imagen para publicar el Catálogo UASD en Render (o cualquier hosting con Docker)
FROM eclipse-temurin:21-jdk

WORKDIR /app

# Drivers de base de datos: PostgreSQL (nube) y SQLite (para importar uasd.db)
ADD https://github.com/pgjdbc/pgjdbc/releases/download/REL42.7.4/postgresql-42.7.4.jar lib/
ADD https://github.com/xerial/sqlite-jdbc/releases/download/3.46.1.3/sqlite-jdbc-3.46.1.3.jar lib/

# Código del proyecto (y uasd.db, si lo subiste)
COPY . .

RUN javac -encoding UTF-8 -cp "lib/*" -d out CatalogoUASD.java

CMD ["sh", "-c", "java -XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8 -cp 'out:lib/*' catalogouasd.CatalogoUASD"]
