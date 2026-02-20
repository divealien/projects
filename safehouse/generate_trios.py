import csv
import random
import sys
import argparse
from collections import defaultdict

def solve_trios(num_players):
    """
    Generates a schedule of trios.
    Constraint 1: Total trios = num_players
    Constraint 2: Every player plays exactly 3 times.
    Constraint 3: No two players pair up more than once.
    """
    
    # Mathematical check:
    # A player plays 3 times. In each game, they meet 2 new people.
    # Total unique people met = 3 * 2 = 6.
    # So there must be at least 6 other people (7 total).
    if num_players < 7:
        print(f"Error: Impossible to satisfy conditions with {num_players} players.", file=sys.stderr)
        print("Each player needs 6 unique partners (3 trios * 2 partners), so minimum players is 7.", file=sys.stderr)
        return None

    # We use a randomized attempt strategy. 
    # Since this is a constraint satisfaction problem, a randomized greedy approach 
    # with restarts is often more effective than naive backtracking for finding *a* solution quickly.
    max_restarts = 10000
    
    for attempt in range(max_restarts):
        trios = []
        player_counts = defaultdict(int) # Tracks how many times a player has played
        played_pairs = set() # Tracks pairs (min, max) to ensure uniqueness
        
        # We need to generate 'num_players' trios
        success = True
        
        # List of all players
        players = list(range(1, num_players + 1))
        
        for _ in range(num_players):
            # Optimization: Prioritize players who have played the least so far
            # to prevent getting stuck with players having 0 games at the end
            candidates = [p for p in players if player_counts[p] < 3]
            
            # Shuffle to ensure random exploration
            random.shuffle(candidates)
            
            # Sort slightly to prioritize those desperate for a game? 
            # Actually, usually random is enough, but sorting by count (descending) helps 
            # fill up 'almost done' players, or ascending to bring 'new' players in.
            # Let's try pure random shuffle first, it's usually robust for N < 100.
            
            found_trio = None
            
            # Greedy search for a valid trio
            # We fix p1, then look for p2, then p3
            for i in range(len(candidates)):
                p1 = candidates[i]
                
                # Check against current pairs is done inside the inner loops
                
                for j in range(i + 1, len(candidates)):
                    p2 = candidates[j]
                    
                    # Check p1-p2 pair
                    pair12 = tuple(sorted((p1, p2)))
                    if pair12 in played_pairs:
                        continue
                        
                    for k in range(j + 1, len(candidates)):
                        p3 = candidates[k]
                        
                        pair13 = tuple(sorted((p1, p3)))
                        pair23 = tuple(sorted((p2, p3)))
                        
                        if pair13 not in played_pairs and pair23 not in played_pairs:
                            found_trio = (p1, p2, p3)
                            break
                    if found_trio: break
                if found_trio: break
            
            if found_trio:
                trios.append(found_trio)
                # Update constraints
                for p in found_trio:
                    player_counts[p] += 1
                
                t1, t2, t3 = found_trio
                played_pairs.add(tuple(sorted((t1, t2))))
                played_pairs.add(tuple(sorted((t1, t3))))
                played_pairs.add(tuple(sorted((t2, t3))))
            else:
                # We got stuck on this attempt (could not find a valid trio)
                success = False
                break
        
        if success:
            return trios

    return None

def get_perfect_matching(adj, num_left, num_right):
    """
    Finds a perfect matching in a bipartite graph using DFS.
    adj: list of lists, where adj[u] contains the neighbors of u (on the right).
    Returns: list `match_r` of size num_right, where match_r[v] = u.
    """
    match_r = [-1] * num_right
    
    def dfs(u, visited):
        for v in adj[u]:
            if not visited[v]:
                visited[v] = True
                if match_r[v] < 0 or dfs(match_r[v], visited):
                    match_r[v] = u
                    return True
        return False

    count = 0
    for u in range(num_left):
        visited = [False] * num_right
        if dfs(u, visited):
            count += 1
            
    if count != num_left:
        # In the context of k-regular bipartite graphs, this should not happen
        # unless the graph construction is wrong.
        return None
        
    return match_r

def organize_trios(trios, num_players):
    """
    Reorders the players within each trio such that:
    - Column 0 contains every player exactly once.
    - Column 1 contains every player exactly once.
    - Column 2 contains every player exactly once.
    
    This is equivalent to decomposing a 3-regular bipartite graph into 3 perfect matchings.
    """
    # Build the initial adjacency list for the bipartite graph
    # Left nodes: Players (0 to num_players-1)
    # Right nodes: Trios (0 to num_players-1)
    # Edge (u, v) exists if player (u+1) is in trio v.
    
    # We use 0-based indexing for logic, but players are 1-based.
    adj = [[] for _ in range(num_players)]
    
    # Map to track which trio index corresponds to which original trio set
    # Actually, we can just look at the 'trios' list.
    for trio_idx, trio in enumerate(trios):
        for player in trio:
            p_idx = player - 1
            adj[p_idx].append(trio_idx)
            
    # We need to determine assignments for 3 columns.
    # col_assignments[trio_idx] = [p1, p2, p3] (ordered)
    col_assignments = [[0]*3 for _ in range(num_players)]
    
    # We will find 3 matchings.
    # For each column c from 0 to 2:
    for c in range(3):
        # Find perfect matching
        match_r = get_perfect_matching(adj, num_players, num_players)
        
        if match_r is None:
            print("Error: Could not decompose into rounds.", file=sys.stderr)
            return None
            
        # match_r[trio_idx] = player_idx assigned to this trio for this column
        for trio_idx, p_idx in enumerate(match_r):
            player = p_idx + 1
            col_assignments[trio_idx][c] = player
            
            # Remove this edge from adj so it's not used in the next column
            # We need to remove trio_idx from adj[p_idx]
            adj[p_idx].remove(trio_idx)
            
    return [tuple(row) for row in col_assignments]

def main():
    parser = argparse.ArgumentParser(description="Generate musical trios from a list of names.")
    parser.add_argument("filename", help="File containing player names (one per line)")
    args = parser.parse_args()
    
    try:
        with open(args.filename, 'r') as f:
            # Read non-empty lines
            names = [line.strip() for line in f if line.strip()]
    except FileNotFoundError:
        print(f"Error: File '{args.filename}' not found.", file=sys.stderr)
        sys.exit(1)
        
    num_players = len(names)
    print(f"Read {num_players} names from {args.filename}.", file=sys.stderr)
    
    # Create a mapping from 1-based index to name
    # Internal logic uses 1...N
    player_map = {i+1: name for i, name in enumerate(names)}
    
    print(f"Attempting to generate {num_players} trios for {num_players} players...", file=sys.stderr)
    
    unordered_trios = solve_trios(num_players)
    
    if unordered_trios:
        print("Trios found. Organizing into rounds...", file=sys.stderr)
        final_trios = organize_trios(unordered_trios, num_players)
        
        if final_trios:
            # Sort by Pos 1 (index 0) to ensure the Trio # matches the file line order for the first player
            # i.e. Trio 1 has Player 1 in Pos 1, Trio 2 has Player 2 in Pos 1...
            final_trios.sort(key=lambda x: x[0])
            
            # CSV Output
            writer = csv.writer(sys.stdout)
            writer.writerow(["Trio", "Pos 1", "Pos 2", "Pos 3"])
            
            for i, trio in enumerate(final_trios, 1):
                row = [
                    f"{i:02d}", 
                    player_map[trio[0]], 
                    player_map[trio[1]], 
                    player_map[trio[2]]
                ]
                writer.writerow(row)

            # Validation (logging to stderr)
            print("Verifying constraints...", file=sys.stderr)
            
            player_freq = defaultdict(int)
            pair_freq = defaultdict(int)
            col_sets = [set(), set(), set()]
            
            for t in final_trios:
                for p in t:
                    player_freq[p] += 1
                
                pairs = [
                    tuple(sorted((t[0], t[1]))),
                    tuple(sorted((t[0], t[2]))),
                    tuple(sorted((t[1], t[2])))
                ]
                for pair in pairs:
                    pair_freq[pair] += 1
                    
                col_sets[0].add(t[0])
                col_sets[1].add(t[1])
                col_sets[2].add(t[2])

            failed = False
            for p in range(1, num_players + 1):
                if player_freq[p] != 3:
                    print(f"FAIL: {player_map[p]} played {player_freq[p]} times (expected 3).", file=sys.stderr)
                    failed = True
            
            for pair, count in pair_freq.items():
                if count > 1:
                    p1_name = player_map[pair[0]]
                    p2_name = player_map[pair[1]]
                    print(f"FAIL: Pair ({p1_name}, {p2_name}) played together {count} times (expected max 1).", file=sys.stderr)
                    failed = True
            
            for i in range(3):
                if len(col_sets[i]) != num_players:
                    print(f"FAIL: Column {i+1} has {len(col_sets[i])} unique players (expected {num_players}).", file=sys.stderr)
                    failed = True
                    
            if not failed:
                print("All constraints passed.", file=sys.stderr)
            else:
                print("Verification failed.", file=sys.stderr)
        else:
            print("Failed to organize trios into valid rounds.", file=sys.stderr)
            
    else:
        print("Could not find a valid configuration after multiple attempts.", file=sys.stderr)
        print("Note: This problem is mathematically impossible for X < 7.", file=sys.stderr)

if __name__ == "__main__":
    main()
