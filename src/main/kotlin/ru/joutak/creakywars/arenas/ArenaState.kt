package ru.joutak.creakywars.arenas

enum class ArenaState {
    WAITING,      // Ожидание игроков
    STARTING,     // Начало игры (обратный отсчет)
    IN_GAME,      // Игра идет
    ENDING,       // Игра заканчивается
    RESETTING     // Арена сбрасывается
}