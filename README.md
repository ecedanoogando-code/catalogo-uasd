# Catálogo de Carreras - UASD
Grupo **Mentes Digitales** · *Innovar, aprender y avanzar*

Integrantes:
- Alinson Fernando Ramírez Navarro - 100570096
- Edwin Cedano Ogando - 100782998
- Jesus Santos - 100588729
- Gregorio Martes Abreu - 100333136

Página web en Java (servidor HTTP incluido en el JDK) con base de datos:
- **En la PC:** SQLite (`uasd.db`)
- **En la nube:** PostgreSQL (Neon), usando la variable de entorno `DATABASE_URL`

Variables de entorno en el hosting:
| Variable | Para qué |
|---|---|
| `DATABASE_URL` | Link de conexión de Neon (`postgresql://...`). Obligatoria en la nube. |
| `ADMIN_CLAVE` | Contraseña inicial del usuario `admin` (opcional). |

La primera vez que arranca en la nube, si la base está vacía y existe `uasd.db`, copia sus datos automáticamente.
