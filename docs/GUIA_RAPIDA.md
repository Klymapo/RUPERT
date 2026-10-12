# Guía rápida: rupert

## ¿Qué es?

Asistente personal para el teléfono, con memoria privada fuera del código público.

## ¿Cómo lo encuentro?

El código de la interfaz está preparado en `web/`, pero **GitHub Pages no aparece habilitado en la configuración comprobada**. No confundir una configuración de despliegue con una app publicada. Hay que habilitar Pages para servirla desde GitHub.

## ¿Dónde está cada cosa?

| Lugar | Qué encontrarás |
|---|---|
| `web/` | La pantalla que usarías en el teléfono |
| `supabase/` | El servicio remoto y la memoria privada |
| `src/` | Las funciones locales opcionales |
| `tests/` | Comprobaciones de funcionamiento |
| `docs/` | Explicaciones de seguridad y arquitectura |

## Qué no debo perder

Nunca subir conversaciones, tokens o claves administrativas. La aplicación puede necesitar conexión a Supabase e internet; no es una asistente totalmente offline.

## Ramas e ideas pendientes

Las nueve ramas adicionales tienen PR ya fusionado. Ninguna corresponde a un trabajo sin integrar.

[Ver la lista completa de ramas y sus decisiones](RAMAS_2026-10-12.md). La documentación histórica se conserva: no hay que entenderla toda para usar el proyecto.
