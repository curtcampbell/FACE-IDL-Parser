package face.idl.model.v4;

import org.apache.commons.lang3.NotImplementedException;

import java.util.Vector;

public class StructType extends ScopedObjectBase implements ITypeSpec{

    public static class Member {
        public Member(ITypeSpec type, String[] declarators) {
            this.type = type;
            this.declarators = declarators;
        }

        public ITypeSpec getType() {
            return type;
        }

        public String[] getDeclarators() {
            return declarators;
        }

        private ITypeSpec type;
        private String[] declarators;
    }

    public StructType(String identifier) {
        super(identifier);
    }

    public boolean hasMember(String declarator) {
        throw new NotImplementedException();
    }

    /**
     *
     * @param type
     * @param declarators
     */
    public void addMember(ITypeSpec type, String[] declarators) {
        members.add(new Member(type, declarators));
    }

    public Vector<Member> getMembers() {
        return members;
    }

    private Vector<Member> members = new Vector<>();

    /**
     * @return
     */
    @Override
    public ScopedObjectKind getKind() {
        return ScopedObjectKind.Struct;
    }
}
