package com.election.ai.model;

import java.util.List;

public record District(int number, String name, List<Constituency> constituencies) {
}
