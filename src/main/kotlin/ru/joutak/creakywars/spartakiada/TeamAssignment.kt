package ru.joutak.creakywars.spartakiada

import org.bukkit.entity.Player
import ru.joutak.creakywars.game.Team

object TeamAssignment {
    
    fun assignPlayersToTeams(players: List<Player>, teams: List<Team>) {
        if (!SpartakiadaManager.isEnabled()) {
            assignRandomly(players, teams)
            return
        }

        val unassigned = mutableListOf<Player>()
        
        for (player in players) {
            val assignedTeamId = SpartakiadaManager.getTeamAssignment(player)
            
            if (assignedTeamId != null && assignedTeamId < teams.size) {
                teams[assignedTeamId].addPlayer(player.uniqueId)
            } else {
                unassigned.add(player)
            }
        }

        if (unassigned.isNotEmpty()) {
            assignRandomly(unassigned, teams)
        }
    }
    
    private fun assignRandomly(players: List<Player>, teams: List<Team>) {
        val shuffled = players.shuffled()

        shuffled.forEach { player ->
            val smallestTeam = teams.minByOrNull { it.players.size }
            smallestTeam?.addPlayer(player.uniqueId)
        }
    }
}