package ubb.proiectColectiv.businessmanagementbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.var;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;
import ubb.proiectColectiv.businessmanagementbackend.model.*;
import ubb.proiectColectiv.businessmanagementbackend.utils.FirebaseUtils;

import java.util.*;
import java.util.stream.Collectors;


@Service
public class UserService {

    private ObjectMapper mapper = new ObjectMapper();
    private HashMap<String, Long> lastLoginAttempts = new HashMap<>();

    // TODO: 11-Dec-19 documentation
    public TokenTransport login(String email, String password) {
        if (lastLoginAttempts.get(email) != null && System.currentTimeMillis() <= lastLoginAttempts.get(email) + 10 * 1000) //check if 10 seconds have passed since last login attempt
            return new TokenTransport(null, LoginResponseValue.SPAM);

        String hashedEmail = String.valueOf(Objects.hash(email));
        Object userInDatabase = FirebaseUtils.getUpstreamData(Arrays.asList("User", hashedEmail)); //get user from Firebase as HashMap
        if (userInDatabase != null) {
            User user = mapper.convertValue(userInDatabase, User.class); //convert HashMap to User POJO
            if (password.equals(user.getPassword())) {

                if (user.getBlockedStatus().equals(true)) {
                    lastLoginAttempts.put(email, System.currentTimeMillis()); //set time of last login attempt for current user
                    return new TokenTransport(null, LoginResponseValue.BLOCKED);
                }

                if (user.getFailedLoginCounter() != 0) {
                    FirebaseUtils.setValue(Arrays.asList("User", hashedEmail, "failedLoginCounter"), 0); //reset counter to 0 cause user entered correct credentials
                }

                if (user.getApprovedStatus().equals(true)) {
                    String token = RandomStringUtils.randomAlphanumeric(15);    //generate token for this user
                    if (TokenService.containsEmail(email)) {
                        TokenService.getTokens().get(email).add(token);
                    } else {
                        TokenService.getTokens().put(email, new ArrayList<>(Collections.singletonList(token)));
                    }
                    return new TokenTransport(token, LoginResponseValue.SUCCESSFUL);
                }
                return new TokenTransport(null, LoginResponseValue.UNAPPROVED);
            }

            lastLoginAttempts.put(email, System.currentTimeMillis()); //set time of last login attempt for current user
            FirebaseUtils.setValue(Arrays.asList("User", hashedEmail, "failedLoginCounter"), user.getFailedLoginCounter() + 1);
            if (user.getFailedLoginCounter() == 2) { //this counter is the one before the incrementation
                FirebaseUtils.setValue(Arrays.asList("User", hashedEmail, "blockedStatus"), Boolean.TRUE);
                return new TokenTransport(null, LoginResponseValue.BLOCKED);
            }
            return new TokenTransport(null, LoginResponseValue.WRONG_PASSWORD);
        }
        return new TokenTransport(null, LoginResponseValue.INEXISTENT);
    }

    /**
     * Creates and saves a user in firebase with email, password, approvalStatus = false, blockedStatus = false, failedLoginCounter = 0
     * @param registeredUser User that wants to register with his credentials
     * @return "REGISTERED" if registration was successful, "EXISTS" if a user with this email already exists
     */
    public String register(User registeredUser) {
        Object userInDataBase = FirebaseUtils.getUpstreamData(Arrays.asList("User", String.valueOf(Objects.hash(registeredUser.getEmail())), "password"));

        if (userInDataBase != null)
            return "EXISTS";

        registeredUser.setApprovedStatus(false);
        registeredUser.setBlockedStatus(false);
        registeredUser.setFailedLoginCounter(0);
        FirebaseUtils.setValue(Arrays.asList("User", String.valueOf(Objects.hash(registeredUser.getEmail()))), registeredUser);
        return "REGISTERED";
    }

    /**
     * Deletes token from Tokens
     * @param email email of the user
     * @param token token to be removed
     * @return "LOGGED OUT" if it was successful, "NOT LOGGED" if token is not in Tokens
     */
    public String logout(String email, String token) {
        if (TokenService.getTokens().get(email).remove(token))
            return "LOGGED OUT";
        else
            return "NOT LOGGED";
    }

    /**
     * Retrieves all personal data of a user from Firebase
     * @param token token of the user
     * @param email email of the user
     * @return all personal data or the message "INVALID TOKEN" if the token is not active
     */
    public Object getPersonalData(String token, String email) {
        if (TokenService.containsToken(email, token)) {
            return FirebaseUtils.getUpstreamData(Arrays.asList("User", String.valueOf(Objects.hash(email))));
        }
        return "INVALID TOKEN";
    }

    /**
     * Retrieves the project experience entries which match the specified email.
     *
     * @param email The email to be matched by the project experience entries.
     *              Identifies the owner of the requested entries.
     * @return Collection of project experience entries which belong to the user identified by the provided email.
     */
    public static List<ProjectExperienceEntry> getAllProjectExperienceEntriesForUserWithEmail(String email) {
        var hashedEmail = Objects.hash(email);
        return getProjectExperienceEntriesForEmailHash(hashedEmail);
    }

    // TODO: 11-Dec-19 documentation
    private static List<ProjectExperienceEntry> getProjectExperienceEntriesForEmailHash(int hashedEmail) {
        var entries = new ArrayList<ProjectExperienceEntry>();
        var nestedExperienceCollection = FirebaseUtils.getNestedCollectionAsUpstreamData(Arrays.asList("ProjectExperience"), false, HashMap.class);
        var keys = nestedExperienceCollection.keySet();
        var projectExperiences = new ArrayList<HashMap>();
        var ids = new ArrayList<String>();
        var idsPos = 0;

        keys.forEach(k -> {
            if (nestedExperienceCollection.get(k).get("userId").toString().equals(String.valueOf(hashedEmail))) {
                projectExperiences.add(nestedExperienceCollection.get(k));
                ids.add(k);
            }
        });

        for (var projectExperienceMap : projectExperiences) {
            var projectExperienceEntry = buildProjectExperienceEntryFromMap(projectExperienceMap, ids.get(idsPos++));
            entries.add(projectExperienceEntry);
        }
        return entries;
    }

    public static ProjectExperienceEntry buildProjectExperienceEntryFromMap(Map<String, Object> projectExperienceMap, String id) {
        var consultingLevelId = projectExperienceMap.get("consultingLevelId");
        var projectId = projectExperienceMap.get("projectId");

        var consultingLevel = FirebaseUtils.getSingleAsUpstream(Arrays.asList("ConsultingLevel", consultingLevelId.toString()), String.class);
        var project = FirebaseUtils.getSingleAsUpstream(Arrays.asList("Project", projectId.toString()), HashMap.class);

        var clientId = project.get("clientId");
        var industryId = project.get("industryId");

        var client = FirebaseUtils.getSingleAsUpstream(Arrays.asList("Client", clientId.toString()), HashMap.class);
        var industry = FirebaseUtils.getSingleAsUpstream(Arrays.asList("Industry", industryId.toString()), String.class);

        var projectExperienceEntry = buildProjectExprienceEntry(id, projectExperienceMap, project, client, industry, consultingLevel);
        return projectExperienceEntry;
    }

    // TODO: 11-Dec-19 documentation
    private static ProjectExperienceEntry buildProjectExprienceEntry(String id, Map<String, Object> entryFirstClassProperties, Map<String, Object> project, Map<String, Object> client, String industry, String consultingLevel) {
        return ProjectExperienceEntry.builder()
                .startDate((Integer) entryFirstClassProperties.get("startDate"))
                .endDate((Integer) entryFirstClassProperties.get("endDate"))
                .consultingLevel(consultingLevel)
                .description((String) entryFirstClassProperties.get("description"))
                .projectStartDate((Integer) project.get("startDate"))
                .projectEndDate((Integer) project.get("endDate"))
                .projectName((String) project.get("name"))
                .industry(industry)
                .clientAddress((String) client.get("address"))
                .clientName((String) client.get("name"))
                .id(id)
                .itemState(ItemState.PERSISTED)
                .build();
    }

    public static void registerPedingingChangesForUserWithEmail(List<ChangeModel> changeModels, String userEmail) {
        List path = Arrays.asList("User", String.valueOf(Objects.hash(userEmail)), "edits");
        FirebaseUtils.setValue(path, changeModels);
    }

    public static List < String > getAllAdminEmails() {
        HashMap < String, HashMap < String, Object > > users = (HashMap < String, HashMap < String, Object > >) FirebaseUtils.getUpstreamData(Arrays.asList("User"));
        List < String > adminEmails = new ArrayList<>();

        for (String hash : users.keySet()) {
            Integer role = -1;
            if (users.get(hash).get("roleId") != null) {
                role = (Integer) users.get(hash).get("roleId");
            }

            if (role == 3) {
                adminEmails.add((String) users.get(hash).get("email"));
            }
        }

        return adminEmails;
    }

    public static List<Project> getAllPossibleProjects() {
        var entries = new ArrayList<Project>();
        var nestedProjectsCollection = FirebaseUtils.getNestedCollectionAsUpstreamData(Arrays.asList("Project"), false, HashMap.class);
        var keys = nestedProjectsCollection.keySet();
        var projects = new ArrayList<HashMap>();
        var ids = new ArrayList<String>();
        var idsPos = 0;

        keys.forEach(k -> {
            projects.add(nestedProjectsCollection.get(k));
            ids.add(k.toString());
        });

        for (var projectExperienceMap : projects) {
            var projectName = projectExperienceMap.get("name").toString();
            var id = ids.get(idsPos++);

            var project = Project.builder()
                    .id(id)
                    .name(projectName)
                    .build();
            entries.add(project);
        }
        return entries;
    }

    public static List<ConsultingLevel> getAllPossibleConsultingLevels() {
        var entries = new ArrayList<ConsultingLevel>();
        var names = FirebaseUtils.getCollectionAsUpstreamData(Arrays.asList("ConsultingLevel"), false, String.class);

        for(var i = 0; i < names.size(); i++) {
            if (names.get(i) != null) {
                var consultingLevel = ConsultingLevel.builder()
                        .id(String.valueOf(i))
                        .name(names.get(i))
                        .build();
                entries.add(consultingLevel);
            }
        }
        return entries;
    }

    public static List<Region> getAllPossibleRegions() {
        var entries = new ArrayList<Region>();
        var names = FirebaseUtils.getCollectionAsUpstreamData(Arrays.asList("Region"), false, String.class);

        for(var i = 0; i < names.size(); i++) {
            if (names.get(i) != null) {
                var region = Region.builder()
                        .id(String.valueOf(i))
                        .name(names.get(i))
                        .build();
                entries.add(region);
            }
        }
        return entries;
    }

    public static FullUserSpecification getFullUserSpecificationForEmail(String email) {
        var hashedEmail = Objects.hash(email);
        var userMap = FirebaseUtils.getSingleAsUpstream(Arrays.asList("User", String.valueOf(hashedEmail)), HashMap.class);

        var firstName = userMap.get("firstName").toString();
        var lastName = userMap.get("lastName").toString();

        var consultingLevelId = userMap.get("consultingLevelId").toString();
        var allConsultingLevels = getAllPossibleConsultingLevels();
        var consultingLevel = allConsultingLevels.stream().filter(x -> x.getId().equals(consultingLevelId)).findFirst().get();

        var regionId = userMap.get("regionId").toString();
        var allRegions = getAllPossibleRegions();
        var region = allRegions.stream().filter(x -> x.getId().equals(regionId)).findFirst().get();

        var skills = getAllSkillsForUserHash(hashedEmail);
        var projectExperience = getAllProjectExperienceEntriesForUserWithEmail(email);

        return FullUserSpecification.builder()
                .consultingLevel(consultingLevel.getName())
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .projectExperience(projectExperience)
                .skills(skills)
                .region(region.getName())
                .build();
    }

    public static List<Skill> getAllSkillsForUserHash(long hash) {
        var entries = new ArrayList<Skill>();
        var usersSkills = FirebaseUtils.getCollectionAsUpstreamData(Arrays.asList("UserSkills"), false, HashMap.class);
        var allPossibleSkillArea = getAllPossibleSkillAreas();
        var allPersistedSkills = getAllPersistedSkills();

        for(var i = 0; i < usersSkills.size(); i++) {
            if (usersSkills.get(i) != null) {
                var userHash = usersSkills.get(i).get("userHash").toString();
                if (String.valueOf(hash).equals(userHash)) {
                    var skillId = usersSkills.get(i).get("skillId").toString();
                    var targetPersistedSkill = allPersistedSkills.stream().filter(x -> x.getId().equals(skillId)).findFirst().get();
                    var areaId = targetPersistedSkill.getSkillAreaId();
                    var targetSkillArea = allPossibleSkillArea.stream().filter(x -> x.getId().equals(areaId)).findAny().get();
                    var skill = Skill.builder()
                            .area(targetSkillArea.getName())
                            .Id(String.valueOf(i))
                            .name(targetPersistedSkill.getName())
                            .itemState(ItemState.PERSISTED)
                            .build();
                    entries.add(skill);
                }
            }
        }
        return entries;
    }

    public static Skill patchSkillWithId(String id) {
        var allSkillAreas = getAllPossibleSkillAreas();
        var allPersistedSkills = getAllPersistedSkills();
        var targetPersisted = allPersistedSkills.stream().filter(p -> p.getId().equals(id)).findFirst().get();
        var targetSkillArea = allSkillAreas.stream().filter(sa -> sa.getId().equals(targetPersisted.getSkillAreaId())).findFirst().get();
        return Skill.builder()
                .itemState(ItemState.PATCHED)
                .area(targetSkillArea.getName())
                .Id(String.valueOf(new Date().getTime()))
                .name(targetPersisted.getName())
                .build();
    }

    public static List<SkillArea> getAllPossibleSkillAreas() {
        var entries = new ArrayList<SkillArea>();
        var names = FirebaseUtils.getCollectionAsUpstreamData(Arrays.asList("SkillArea"), false, String.class);

        for(var i = 0; i < names.size(); i++) {
            if (names.get(i) != null) {
                var skillArea = SkillArea.builder()
                        .id(String.valueOf(i))
                        .name(names.get(i))
                        .build();
                entries.add(skillArea);
            }
        }
        return entries;
    }

    public static List<PersistedSkill> getAllPersistedSkills() {
        var entries = new ArrayList<PersistedSkill>();
        var args = FirebaseUtils.getCollectionAsUpstreamData(Arrays.asList("Skill"), false, HashMap.class);

        for(var i = 0; i < args.size(); i++) {
            if (args.get(i) != null) {
                var skillAreaId = args.get(i).get("skillAreaId").toString();
                PersistedSkill persistedSkill = PersistedSkill.builder()
                        .id(String.valueOf(i))
                        .name(args.get(i).get("name").toString())
                        .skillAreaId(skillAreaId)
                        .build();
                entries.add(persistedSkill);
            }
        }
        return entries;
    }

    public static FullUserSpecification createDiff(String email) {
        var allStrategies = CompileChangeStrategiesFactory.createStrategies();
        var changes = getChangeModelsForHash(String.valueOf(Objects.hash(email)));
        final FullUserSpecification fullUserSpecification = getFullUserSpecificationForEmail(email);
        if (changes == null || changes.size() == 0) {
            return fullUserSpecification;
        }
        changes.forEach(c -> {
            if (c != null) {
                allStrategies.forEach(s -> {
                    if (s.CanCompile(c)) {
                        s.Preview(email, fullUserSpecification, c);
                    }
                });
            }
        });

        return fullUserSpecification;
    }

    private static List<ChangeModel> getChangeModelsForHash(String hash) {
        var changeModelMaps = FirebaseUtils.getCollectionAsUpstreamData(Arrays.asList("User", hash, "edits"), false, HashMap.class);
        if (changeModelMaps == null) {
            return null;
        }
        List<ChangeModel> changeModels = changeModelMaps.stream().filter(m -> m != null).map(m -> {
            var args = m.get("args");
            var changeType = ChangeType.valueOf(m.get("changeType").toString());
            var resource = Resource.valueOf(m.get("resource").toString());
            return ChangeModel.builder().args((HashMap) args).changeType(changeType).resource(resource).build();
        }).collect(Collectors.toList());
        return changeModels;
    }

    public static void acceptChanges(String email) {
        var allStrategies = CompileChangeStrategiesFactory.createStrategies();
        var changes = getChangeModelsForHash(String.valueOf(Objects.hash(email)));
        if (changes == null) {
            return;
        }
        changes.forEach(c -> {
            if (c != null) {
                allStrategies.forEach(s -> {
                    if (s.CanCompile(c)) {
                        s.Persist(email, c);
                    }
                });
            }
        });

        var hash = String.valueOf(Objects.hash(email));
        FirebaseUtils.setValue(Arrays.asList("User", hash, "edits"), null);
    }

    public static String getNextUserSkillId() {
        var allEntries = FirebaseUtils.getCollectionAsUpstreamData(Arrays.asList("UserSkills"), false, Object.class);
        return String.valueOf(allEntries.size());
    }
}
