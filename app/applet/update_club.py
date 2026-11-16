path = 'app/src/main/java/com/example/ui/screens/club/ClubScreen.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

old_create = """                                         if (res.isSuccess) {
                                             val b = res.getOrNull()!!
                                             currentClubState = ClubState(
                                                 clubId = b.clubId,
                                                 code = b.code,
                                                 name = b.name,
                                                 ownerUserId = b.ownerUserId,
                                                 ownerUsername = b.ownerUsername,
                                                 members = listOf(com.example.model.ClubMember(b.ownerUserId, b.ownerUsername, true))
                                             )
                                             isInClub = true
                                             Toast.makeText(context, "Club created! Share code: ${b.code}", Toast.LENGTH_LONG).show()"""

new_create = """                                         if (res.isSuccess) {
                                             val b = res.getOrNull()!!
                                             repository.clubManager.startClub(b.clubId)
                                             Toast.makeText(context, "Club created! Share code: ${b.code}", Toast.LENGTH_LONG).show()"""

if old_create in content:
    content = content.replace(old_create, new_create)
    print("Replaced create block successfully")
else:
    print("WARNING: old_create not found exactly")

old_join = """                                         if (res.isSuccess) {
                                             val b = res.getOrNull()!!
                                             // Immediately pull full state
                                             val stateRes = repository.getClubState(b.clubId)
                                             if (stateRes.isSuccess) {
                                                 currentClubState = stateRes.getOrNull()
                                                 isInClub = true
                                                 Toast.makeText(context, "Joined ${b.name}!", Toast.LENGTH_SHORT).show()
                                             }"""

new_join = """                                         if (res.isSuccess) {
                                             val b = res.getOrNull()!!
                                             repository.clubManager.startClub(b.clubId)
                                             Toast.makeText(context, "Joined ${b.name}!", Toast.LENGTH_SHORT).show()"""

if old_join in content:
    content = content.replace(old_join, new_join)
    print("Replaced join block successfully")
else:
    print("WARNING: old_join not found exactly")

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Done")
