package com.gpsquest96.route;

/**
 * Error de esquema de ruta con motivo legible (spec 004 §11, slice 0).
 * El motivo se muestra/loguea; nunca se acepta una ruta a medias.
 */
public class RouteFormatException extends Exception {

    public RouteFormatException(String reason) {
        super(reason);
    }
}
